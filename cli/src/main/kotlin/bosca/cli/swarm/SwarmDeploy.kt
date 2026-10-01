package bosca.cli.swarm

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

private fun shell(value: String) = "'" + value.replace("'", "'\\''") + "'"

/**
 * Runs [command], feeding it [input]. With [capture], returns standard output only: SSH and sudo write
 * routine warnings (a newly added host key, an unresolvable hostname) to standard error, and those must
 * never leak into values such as Swarm join tokens. Captured standard error is drained concurrently, echoed
 * to the operator's terminal, and included in the failure message. Without [capture], both streams go
 * straight to the terminal.
 */
internal fun runProcess(command: List<String>, input: ByteArray? = null, capture: Boolean = false): String {
    val builder = ProcessBuilder(command)
    if (!capture) {
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        builder.redirectError(ProcessBuilder.Redirect.INHERIT)
    }
    val process = builder.start()
    val stderr = if (capture) {
        CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText().trim() }
    } else {
        CompletableFuture.completedFuture("")
    }
    process.outputStream.use { stream -> if (input != null) stream.write(input) }
    val output = if (capture) process.inputStream.bufferedReader().readText().trim() else ""
    val code = process.waitFor()
    val errors = stderr.join()
    if (errors.isNotEmpty()) System.err.println(errors)
    require(code == 0) {
        "Command failed ($code): ${command.take(6).joinToString(" ")}" +
            listOf(output, errors).filter(String::isNotEmpty).joinToString("\n", prefix = "\n").takeIf { capture && it.isNotBlank() }.orEmpty()
    }
    return output
}

/** Runs a command on a node; the deployment talks to its nodes only through this. */
internal fun interface RemoteShell {
    fun run(target: String, command: String, input: ByteArray?, capture: Boolean): String
}

/** Runs remote commands over non-interactive SSH. */
internal object SshRemoteShell : RemoteShell {
    override fun run(target: String, command: String, input: ByteArray?, capture: Boolean): String =
        runProcess(listOf("ssh", "-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", target, command), input, capture)
}

/** The shell used for every remote command; tests substitute a recording fake. */
@Volatile
internal var remoteShell: RemoteShell = SshRemoteShell

private fun ssh(target: String, command: String, input: ByteArray? = null, capture: Boolean = false): String =
    remoteShell.run(target, command, input, capture)

private fun script(target: String, body: String, capture: Boolean = false) =
    ssh(target, "bash -se", body.toByteArray(Charsets.UTF_8), capture)

private val dockerBootstrap = """set -euo pipefail
sudo -n true
if ! command -v docker >/dev/null 2>&1; then
  . /etc/os-release
  case "${'$'}ID" in
    ubuntu|debian) ;;
    *) echo "Automatic Docker installation supports Ubuntu and Debian; install Docker Engine first on ${'$'}ID" >&2; exit 1 ;;
  esac
  sudo -n apt-get update
  sudo -n apt-get install -y ca-certificates curl
  sudo -n install -m 0755 -d /etc/apt/keyrings
  sudo -n curl -fsSL "https://download.docker.com/linux/${'$'}ID/gpg" -o /etc/apt/keyrings/docker.asc
  sudo -n chmod a+r /etc/apt/keyrings/docker.asc
  codename="${'$'}{UBUNTU_CODENAME:-${'$'}VERSION_CODENAME}"
  arch="${'$'}(dpkg --print-architecture)"
  printf 'Types: deb\nURIs: https://download.docker.com/linux/%s\nSuites: %s\nComponents: stable\nArchitectures: %s\nSigned-By: /etc/apt/keyrings/docker.asc\n' "${'$'}ID" "${'$'}codename" "${'$'}arch" | sudo -n tee /etc/apt/sources.list.d/docker.sources >/dev/null
  sudo -n apt-get update
  sudo -n apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
sudo -n systemctl enable --now docker
sudo -n docker version --format '{{.Server.Version}}' >/dev/null
"""

/** Opens SSH, the private Swarm network, and (on the manager) Caddy's public ports before joining nodes. */
internal fun firewallScript(config: SwarmConfig, nodeAddress: String?, manager: Boolean): String {
    val privateAddress = nodeAddress?.takeIf(String::isNotBlank)
    val interfaceLookup = if (privateAddress != null) {
        "ip -o -4 addr show | awk -v address=${shell(privateAddress)} '{split(\$4, parts, \"/\"); if (parts[1] == address) {print \$2; exit}}'"
    } else {
        "ip -4 route get ${shell(config.manager.advertiseAddress)} | awk '{for (i=1; i<=NF; i++) if (\$i == \"dev\") {print \$(i+1); exit}}'"
    }
    val cidrLookup = if (privateAddress != null) {
        "ip -4 route show dev \"\$private_interface\" scope link | awk 'NR == 1 {print \$1}'"
    } else {
        "printf '%s\\n' ${shell(config.manager.advertiseAddress + "/32")}"
    }
    return """set -euo pipefail
sudo -n true
if ! command -v ufw >/dev/null 2>&1; then
  sudo -n apt-get update
  sudo -n apt-get install -y ufw
fi
private_interface=${'$'}($interfaceLookup)
test -n "${'$'}private_interface" || { echo 'Cannot identify the private network interface' >&2; exit 1; }
private_cidr=${'$'}($cidrLookup)
test -n "${'$'}private_cidr" || { echo 'Cannot identify the private network CIDR' >&2; exit 1; }
ssh_connection=${'$'}{SSH_CONNECTION:-}
ssh_port=${'$'}{ssh_connection##* }
case "${'$'}ssh_port" in ''|*[!0-9]*) ssh_port=22;; esac
sudo -n ufw allow "${'$'}{ssh_port}/tcp"
sudo -n ufw allow in on "${'$'}private_interface" from "${'$'}private_cidr" to any
sudo -n ufw allow out on "${'$'}private_interface" to "${'$'}private_cidr"
${if (manager) "sudo -n ufw allow 80/tcp\nsudo -n ufw allow 443/tcp" else ""}
sudo -n ufw --force enable
"""
}

internal fun bootstrapNodes(config: SwarmConfig) {
    val manager = config.manager.ssh
    for ((target, address) in listOf(manager to config.manager.advertiseAddress) + config.workers.map { it.ssh to it.advertiseAddress }) {
        println("Configuring firewall on $target")
        script(target, firewallScript(config, address, target == manager))
        println("Checking Docker Engine on $target")
        script(target, dockerBootstrap)
    }
    val state = script(manager, "sudo -n docker info --format '{{.Swarm.LocalNodeState}} {{.Swarm.ControlAvailable}}'", true)
    when {
        state.startsWith("inactive") -> script(manager, "sudo -n docker swarm init --advertise-addr ${shell(config.manager.advertiseAddress)}")
        state != "active true" -> error("Manager host is already in an incompatible Swarm state: $state")
    }
    val workerToken = script(manager, "sudo -n docker swarm join-token -q worker", true)
    for (worker in config.workers) {
        // The embeddings and TensorFlow Serving images are published for amd64 only.
        val architecture = script(worker.ssh, "uname -m", true)
        require(architecture == "x86_64") { "${worker.ssh} is $architecture; worker nodes must be x86_64 (amd64)" }
        val workerState = script(worker.ssh, "sudo -n docker info --format '{{.Swarm.LocalNodeState}}'", true)
        when (workerState) {
            "inactive" -> {
                val addresses = worker.advertiseAddress.takeIf(String::isNotEmpty)?.let {
                    " --advertise-addr ${shell(it)} --data-path-addr ${shell(it)}"
                }.orEmpty()
                script(worker.ssh, "sudo -n docker swarm join --token ${shell(workerToken)}$addresses ${shell(config.manager.advertiseAddress + ":2377")}")
            }
            "active" -> {
                val nodeId = script(worker.ssh, "sudo -n docker info --format '{{.Swarm.NodeID}}'", true)
                val managerNodeIds = script(manager, "sudo -n docker node ls -q", true)
                    .lineSequence().filter(String::isNotBlank).toSet()
                require(nodeId.isNotBlank() && nodeId in managerNodeIds) {
                    "${worker.ssh} is already in a different Swarm"
                }
            }
            "error" -> error("Swarm worker ${worker.ssh} is in error state: " +
                script(worker.ssh, "sudo -n docker info --format '{{.Swarm.Error}}'", true))
            else -> error("Unexpected Swarm state on ${worker.ssh}: $workerState")
        }
    }
    val nodeId = script(manager, "sudo -n docker info --format '{{.Swarm.NodeID}}'", true)
    script(manager, "sudo -n docker node update --label-add bosca_data=true ${shell(nodeId)}")
    // The model loader and TensorFlow Serving share a local volume, so exactly one worker carries the label.
    val modelNode = script(config.workers.first().ssh, "sudo -n docker info --format '{{.Swarm.NodeID}}'", true)
    script(manager, modelNodeLabelScript(modelNode))
}

/** Checks the existing Swarm and its placement labels without installing Docker or changing nodes. */
internal fun verifyBootstrappedNodes(config: SwarmConfig) {
    val manager = config.manager.ssh
    val managerState = script(manager, "sudo -n docker info --format '{{.Swarm.LocalNodeState}} {{.Swarm.ControlAvailable}}'", true)
    require(managerState == "active true") {
        "Swarm manager $manager is not ready ($managerState); run bosca swarm bootstrap first"
    }
    val managerNodeId = script(manager, "sudo -n docker info --format '{{.Swarm.NodeID}}'", true)
    val managerNodeIds = script(manager, "sudo -n docker node ls -q", true)
        .lineSequence().filter(String::isNotBlank).toSet()
    require(managerNodeId.isNotBlank() && managerNodeId in managerNodeIds) {
        "Swarm manager $manager has no registered node; run bosca swarm bootstrap first"
    }
    val dataLabel = script(manager,
        "sudo -n docker node inspect ${shell(managerNodeId)} --format '{{index .Spec.Labels \"bosca_data\"}}'", true)
    require(dataLabel == "true") { "Swarm manager $manager lacks bosca_data=true; run bosca swarm bootstrap first" }
    for ((index, worker) in config.workers.withIndex()) {
        val architecture = script(worker.ssh, "uname -m", true)
        require(architecture == "x86_64") { "${worker.ssh} is $architecture; worker nodes must be x86_64 (amd64)" }
        val state = script(worker.ssh, "sudo -n docker info --format '{{.Swarm.LocalNodeState}}'", true)
        require(state == "active") { "Swarm worker ${worker.ssh} is $state; run bosca swarm bootstrap first" }
        val nodeId = script(worker.ssh, "sudo -n docker info --format '{{.Swarm.NodeID}}'", true)
        require(nodeId.isNotBlank() && nodeId in managerNodeIds) {
            "${worker.ssh} is already in a different Swarm"
        }
        if (worker.advertiseAddress.isNotEmpty()) {
            val nodeAddress = script(worker.ssh, "sudo -n docker info --format '{{.Swarm.NodeAddr}}'", true)
            require(nodeAddress == worker.advertiseAddress) {
                "Swarm worker ${worker.ssh} advertises $nodeAddress, expected ${worker.advertiseAddress}"
            }
        }
        if (index == 0) {
            val mlLabel = script(manager,
                "sudo -n docker node inspect ${shell(nodeId)} --format '{{index .Spec.Labels \"bosca_ml\"}}'", true)
            require(mlLabel == "true") {
                "Swarm worker ${worker.ssh} lacks bosca_ml=true; run bosca swarm bootstrap first"
            }
        }
    }
}

/** Labels [modelNode] `bosca_ml=true` and removes the label from every other node. */
internal fun modelNodeLabelScript(modelNode: String) = """set -euo pipefail
for node in ${'$'}(sudo -n docker node ls -q --filter node.label=bosca_ml=true); do
  if [ "${'$'}node" != ${shell(modelNode)} ]; then sudo -n docker node update --label-rm bosca_ml "${'$'}node"; fi
done
sudo -n docker node update --label-add bosca_ml=true ${shell(modelNode)}
"""

private fun packageBundle(bundle: Path): ByteArray {
    // COPYFILE_DISABLE and --no-xattrs keep macOS AppleDouble (`._*`) entries and extended attributes out
    // of the archive; Trino would otherwise try to load `._<site>_bosca.properties` as a catalog.
    val builder = ProcessBuilder("tar", "--no-xattrs", "-cz", "-C", bundle.toString(), ".")
    builder.environment()["COPYFILE_DISABLE"] = "1"
    val process = builder.start()
    val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
    val bytes = process.inputStream.readAllBytes()
    require(process.waitFor() == 0) { "Could not package generated files: ${stderr.join()}" }
    return bytes
}

/** Numeric `uid:gid` of an image's default user, so bind-mounted files can stay private to that user. */
private fun imageOwner(image: String) =
    "\"${'$'}(sudo -n docker run --rm --entrypoint id ${shell(image)} -u):${'$'}(sudo -n docker run --rm --entrypoint id ${shell(image)} -g)\""

private fun uploadDirectory(config: SwarmConfig) = "${config.dataRoot}/.upload"

/**
 * Remote command that unpacks the bundle read from standard input into a root-only staging directory.
 * Containers reach their bind mounts without traversing the host path, so a root-only data root hides
 * every generated secret from other host accounts without affecting the services. `--no-same-owner`
 * stops the operator's local uid from owning the files on the VM, and `._*` entries are dropped in case
 * a macOS archive still carries AppleDouble files.
 */
internal fun extractBundleCommand(config: SwarmConfig): String {
    val root = shell(config.dataRoot)
    val staging = shell(uploadDirectory(config))
    return "bash -c " + shell("""set -eu
sudo -n install -d -m 0700 $root
sudo -n chmod 0700 $root
sudo -n rm -rf $staging
sudo -n install -d -m 0700 $staging
sudo -n tar -xz --no-same-owner --warning=no-unknown-keyword --exclude='._*' -C $staging
""")
}

/**
 * Shell function `sync_tree <staged> <live>` that makes the live directory match the staged one.
 * Existing files are overwritten in place rather than replaced, so a running container's bind mount keeps
 * seeing the current file instead of a deleted inode. Files and directories absent from the staged tree
 * are removed so a removed site leaves no stale catalog behind. Uses only portable `cp` and `find`.
 */
internal fun syncTreeFunction(sudo: String = "sudo -n") = """
sync_tree() {
  $sudo mkdir -p "$2"
  $sudo cp -R "$1/." "$2/"
  $sudo find "$2" -type f | while IFS= read -r live; do
    $sudo test -e "$1/${'$'}{live#"$2"/}" || $sudo rm -f "${'$'}live"
  done
  $sudo find "$2" -mindepth 1 -type d -empty -delete
}
"""

/** Remote script that installs the staged bundle, creates the data and bucket directories, and sets ownership. */
internal fun installBundleScript(config: SwarmConfig): String {
    val root = config.dataRoot
    val staging = uploadDirectory(config)
    val images = config.images
    val directories = listOf("postgres", "nats", "redis", "meilisearch", "s3", "trino", "caddy-data", "caddy-config")
        .joinToString(" ") { shell("$root/$it") }
    // S3Proxy's filesystem backend treats each directory under its base directory as a bucket; a directory
    // readable by others is reported as public-read, so buckets stay owner-only.
    val buckets = config.sites.flatMap { listOf("${it.id}-storage", "${it.id}-warehouse") }
        .joinToString(" ") { shell("$root/s3/$it") }
    return """set -euo pipefail
${syncTreeFunction()}
sync_tree ${shell("$staging/config")} ${shell("$root/config")}
sync_tree ${shell("$staging/stacks")} ${shell("$root/stacks")}
for file in buckets.json .bosca-swarm-generated; do
  sudo -n cp ${shell(staging)}/"${'$'}file" ${shell(root)}/"${'$'}file"
done
sudo -n rm -rf ${shell(staging)}
# Service data directories keep 0755: images such as PostgreSQL drop to an unprivileged user that must
# traverse the mount root. The 0700 data root already hides them from other host accounts.
sudo -n install -d -m 0755 $directories ${shell("$root/meilisearch/snapshots")}
# Database dumps and backup state stay root-only.
sudo -n install -d -m 0700 ${shell("$root/backups")} ${shell("$root/backup-state")}
s3_owner=${imageOwner(images.getValue("s3proxy"))}
sudo -n install -d -m 0700 $buckets
sudo -n chown "${'$'}s3_owner" $buckets
trino_owner=${imageOwner(images.getValue("trino"))}
sudo -n chown -R "${'$'}trino_owner" ${shell("$root/config/trino")} ${shell("$root/trino")}
sudo -n find ${shell("$root/config/trino")} -type d -exec chmod 0700 {} +
sudo -n find ${shell("$root/config/trino")} -type f -exec chmod 0600 {} +
nats_owner=${imageOwner(images.getValue("nats"))}
sudo -n chown "${'$'}nats_owner" ${shell("$root/config/nats.conf")} ${shell("$root/nats")}
sudo -n chmod 0600 ${shell("$root/config/nats.conf")}
pgbouncer_owner=${imageOwner(images.getValue("pgbouncer"))}
sudo -n chown "${'$'}pgbouncer_owner" ${shell("$root/config/pgbouncer/userlist.txt")}
sudo -n chmod 0600 ${shell("$root/config/pgbouncer/userlist.txt")}
sudo -n chmod 0644 ${shell("$root/config/Caddyfile")}
"""
}

private fun uploadBundle(config: SwarmConfig, bundle: Path) {
    ssh(config.manager.ssh, extractBundleCommand(config), packageBundle(bundle))
    script(config.manager.ssh, installBundleScript(config))
}

internal fun registryPassword(auth: SwarmRegistryAuth, environment: (String) -> String? = System::getenv): String =
    auth.password.ifBlank { environment(auth.passwordEnv).orEmpty() }

internal fun registryAuths(config: SwarmConfig): List<SwarmRegistryAuth> =
    (listOf(config.registryAuth) + config.sites.mapNotNull(SwarmSite::rootRegistryAuth))
        .filter { it.username.isNotBlank() }

private fun registryLogin(config: SwarmConfig) {
    for (auth in registryAuths(config)) {
        val password = registryPassword(auth)
        require(password.isNotBlank()) {
            "Set a registry password for ${auth.server} in the private config or ${auth.passwordEnv} before deployment"
        }
        ssh(config.manager.ssh,
            "sudo -n docker login --username ${shell(auth.username)} --password-stdin ${shell(auth.server)}",
            (password + "\n").toByteArray())
    }
}

/**
 * Submits a stack without waiting: `docker stack deploy --detach=false` waits indefinitely for a service
 * whose health check never passes, so [waitServices] does the waiting with a deadline and a diagnosis.
 */
private fun stackDeploy(config: SwarmConfig, name: String) {
    script(config.manager.ssh,
        "sudo -n docker stack deploy --detach=true --with-registry-auth --resolve-image changed -c ${shell("${config.dataRoot}/stacks/$name.yml")} ${shell(name)}")
}

private fun postgresQuery(container: String, database: String, query: String): String {
    val command = "PGPASSWORD=\"\$POSTGRES_PASSWORD\" psql -v ON_ERROR_STOP=1 -At -h 127.0.0.1 " +
        "-U \"\$POSTGRES_USER\" -d ${shell(database)} -c ${shell(query)}"
    return "sudo -n docker exec ${shell(container)} sh -c ${shell(command)}"
}

/** Checks preexisting clusters before adopting the persistent bootstrap marker. */
internal fun databaseInventoryScript(config: SwarmConfig, container: String): String = buildString {
    val roles = config.sites.flatMap { listOf(it.id, "${it.id}_warehouse") }
    val databases = config.sites.flatMap { listOf("${it.id}-bosca", "${it.id}-warehouse") }
    val query = "SELECT (SELECT count(*) FROM pg_roles WHERE rolname IN (${roles.joinToString(",") { "'$it'" }})) = ${roles.size} " +
        "AND (SELECT count(*) FROM pg_database WHERE datname IN (${databases.joinToString(",") { "'$it'" }})) = ${databases.size}"
    appendLine("set -euo pipefail")
    appendLine("if [ \"\$(${postgresQuery(container, "postgres", query)})\" != t ]; then echo incomplete; exit 0; fi")
    for (site in config.sites) {
        val extensions = "SELECT count(*) = 2 FROM pg_extension WHERE extname IN ('unaccent', 'vector')"
        appendLine("if [ \"\$(${postgresQuery(container, "${site.id}-bosca", extensions)})\" != t ]; then echo incomplete; exit 0; fi")
    }
    appendLine("echo complete")
}

private fun writeDatabaseBootstrapMarker(config: SwarmConfig, fingerprint: String) {
    val marker = shell("${config.dataRoot}/database-bootstrap.sha256")
    script(config.manager.ssh, """set -euo pipefail
temporary=${'$'}(sudo -n mktemp ${shell("${config.dataRoot}/database-bootstrap.sha256.XXXXXX")})
trap 'sudo -n rm -f "${'$'}temporary"' EXIT
printf '%s\n' ${shell(fingerprint)} | sudo -n tee "${'$'}temporary" >/dev/null
sudo -n mv "${'$'}temporary" $marker
""")
}

internal fun bootstrapDatabases(config: SwarmConfig) {
    val marker = shell("${config.dataRoot}/database-bootstrap.sha256")
    val fingerprint = databaseBootstrapFingerprint(config)
    val current = script(config.manager.ssh, "sudo -n cat $marker 2>/dev/null || true", true)
    if (current == fingerprint) {
        println("Database bootstrap already applied; skipping.")
        return
    }
    val container = script(config.manager.ssh, """set -euo pipefail
for attempt in {1..60}; do
  container="${'$'}(sudo -n docker ps -q --filter label=com.docker.swarm.service.name=infra_postgres | head -n 1)"
  if [ -n "${'$'}container" ] && sudo -n docker exec "${'$'}container" pg_isready -h 127.0.0.1 -U bosca_admin -d postgres >/dev/null 2>&1; then
    printf '%s\n' "${'$'}container"
    exit 0
  fi
  sleep 5
done
echo 'PostgreSQL did not become ready' >&2
exit 1
""", true)
    if (current.isBlank() && script(config.manager.ssh, databaseInventoryScript(config, container), true) == "complete") {
        writeDatabaseBootstrapMarker(config, fingerprint)
        println("Existing databases already bootstrapped; skipping.")
        return
    }
    script(config.manager.ssh,
        "set -euo pipefail\nsudo -n cat ${shell("${config.dataRoot}/config/db-bootstrap.sh")} | sudo -n docker exec -i ${shell(container)} sh")
    writeDatabaseBootstrapMarker(config, fingerprint)
}

/** Every service the deployment runs, as `docker service ls` names them. */
internal fun expectedServices(config: SwarmConfig): Set<String> = buildSet {
    addAll(infraStack(config).serviceNames().map { "infra_$it" })
    addAll(edgeStack(config).serviceNames().map { "edge_$it" })
    config.sites.forEach { site -> addAll(siteStack(config, site).serviceNames().map { "${site.id}_$it" }) }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.serviceNames() = (getValue("services") as Map<String, Any>).keys

/**
 * Waits until every service reports one healthy replica and finishes its update. On timeout the error
 * names each lagging service with its latest task state and error.
 */
internal fun waitServices(
    config: SwarmConfig,
    timeoutMillis: Long = 1_200_000,
    pollMillis: Long = 5_000,
    expected: Set<String> = expectedServices(config),
) {
    // Tasks with a health check count only once healthy; the embeddings model downloads on first start.
    val deadline = System.currentTimeMillis() + timeoutMillis
    var current = emptyMap<String, String>()
    var updateStates = emptyMap<String, String>()
    var lastProgress = ""
    var lastPrintedAt = 0L
    val inspect = "sudo -n docker service inspect --format '{{.Spec.Name}}|{{if .UpdateStatus}}{{.UpdateStatus.State}}{{end}}' " +
        expected.sorted().joinToString(" ", transform = ::shell)
    while (true) {
        current = script(config.manager.ssh, "sudo -n docker service ls --format '{{.Name}}|{{.Replicas}}'", true)
            .lineSequence().mapNotNull { line -> line.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        updateStates = script(config.manager.ssh, inspect, true).lineSequence()
            .mapNotNull { line -> line.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val failedUpdate = expected.firstOrNull { updateStates[it] in setOf("paused", "rollback_started", "rollback_paused", "rollback_completed") }
        if (failedUpdate != null) error("Swarm service $failedUpdate update ${updateStates.getValue(failedUpdate)}")
        val lagging = expected.filter { current[it] != "1/1" || updateStates[it] == "updating" }.sorted()
        if (lagging.isEmpty()) return
        val progress = lagging.joinToString(", ") { service ->
            val state = updateStates[service].orEmpty()
            "$service (${current[service] ?: "missing"}${if (state.isEmpty()) "" else ", $state"})"
        }
        val now = System.currentTimeMillis()
        if (progress != lastProgress || now - lastPrintedAt >= 30_000) {
            println("Waiting for Swarm services: $progress")
            lastProgress = progress
            lastPrintedAt = now
        }
        if (now >= deadline) break
        Thread.sleep(pollMillis)
    }
    val lagging = expected.filter { current[it] != "1/1" || updateStates[it] == "updating" }.sorted()
    val details = lagging.joinToString("\n") { service ->
        val task = script(config.manager.ssh,
            "sudo -n docker service ps ${shell(service)} --no-trunc --format '{{.CurrentState}} {{.Error}}' | head -n 1", true)
        "  $service (${current[service] ?: "missing"}, update ${updateStates[service].orEmpty().ifBlank { "none" }}): ${task.ifBlank { "no task" }}"
    }
    error("Swarm services did not finish updating or reach one healthy replica:\n$details")
}

/** Returns `null` when [url] answers HTTP 200, otherwise a description of why it is not ready. */
internal fun readinessFailure(url: String, timeoutMillis: Int = 10_000): String? = try {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = timeoutMillis
    connection.readTimeout = timeoutMillis
    try {
        val status = connection.responseCode
        if (status == 200) null else "HTTP $status"
    } finally {
        connection.disconnect()
    }
} catch (e: java.io.IOException) {
    // The service may still be starting or DNS may not have propagated; the caller retries until its deadline.
    "${e::class.java.simpleName}: ${e.message}"
}

internal fun publicReadinessUrls(config: SwarmConfig) = config.sites.flatMap { site ->
    val hosts = site.hosts
    if (site.rootImage.isBlank()) {
        listOf("https://${hosts.root}/api/v1/ready", "https://${hosts.studio}/health",
            "https://${hosts.profiles}/login", "https://${hosts.notifications}/")
    } else {
        listOf("https://${hosts.api}/api/v1/ready", "https://${hosts.studio}/health",
            "https://${hosts.profiles}/login", "https://${hosts.notifications}/",
            "https://${hosts.root}/", "https://${hosts.www}/")
    }
}

private fun checkPublicSites(config: SwarmConfig) {
    val urls = publicReadinessUrls(config)
    for (url in urls) {
        val deadline = System.currentTimeMillis() + 300_000
        var failure = readinessFailure(url)
        var lastPrintedAt = 0L
        while (failure != null && System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            if (now - lastPrintedAt >= 30_000) {
                println("Waiting for public readiness: $url ($failure)")
                lastPrintedAt = now
            }
            Thread.sleep(5_000)
            failure = readinessFailure(url)
        }
        require(failure == null) { "Public readiness check failed: $url (last result: $failure)" }
        println("Ready: $url")
    }
}

internal fun deploySwarm(config: SwarmConfig, bundle: Path, skipPublicCheck: Boolean) {
    verifyBootstrappedNodes(config)
    registryLogin(config)
    uploadBundle(config, bundle)
    script(config.manager.ssh,
        "sudo -n docker network inspect bosca >/dev/null 2>&1 || sudo -n docker network create --driver overlay --attachable bosca")
    stackDeploy(config, "infra")
    waitServices(config, expected = setOf("infra_postgres", "infra_pgbouncer"))
    bootstrapDatabases(config)
    config.sites.forEach { stackDeploy(config, it.id) }
    stackDeploy(config, "edge")
    waitServices(config)
    if (!skipPublicCheck) checkPublicSites(config)
    deploymentNotices(config).forEach { println("Notice: $it") }
    println("Swarm deployment is running. Verify Git, search, analytics, mail, and backups before use.")
}

internal fun swarmStatus(config: SwarmConfig): String {
    val services = script(config.manager.ssh, "sudo -n docker service ls --format '{{.Name}} {{.Replicas}}'", true)
    if (config.backup.repository.isBlank()) return "$services\nBackups: not configured"
    val state = shell("${config.dataRoot}/backup-state")
    val backups = script(config.manager.ssh, """set -eu
success=${'$'}(sudo -n cat $state/last-success 2>/dev/null || echo never)
failure=${'$'}(sudo -n cat $state/last-failure 2>/dev/null || true)
echo "Last successful backup: ${'$'}success"
if [ -n "${'$'}failure" ]; then echo "Last failed backup: ${'$'}failure"; fi
""", true)
    return "$services\n$backups"
}

/** Operator notices for features that need configuration after the first deployment. */
internal fun deploymentNotices(config: SwarmConfig): List<String> = buildList {
    if (config.backup.repository.isBlank()) {
        add("Backups are not configured; set backup.repository to enable nightly off-VM backups.")
    }
    config.sites.filter { site ->
        listOf(site.ml.boscaToken, site.ml.artifactsPushToken, site.ml.artifactsPullToken).any(String::isBlank)
    }.forEach { site ->
        add("Recommendation training for ${site.id} needs ml.boscaToken, ml.artifactsPushToken and ml.artifactsPullToken.")
    }
}
