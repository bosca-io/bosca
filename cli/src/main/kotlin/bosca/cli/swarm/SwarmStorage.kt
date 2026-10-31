package bosca.cli.swarm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val storageJson = Json { ignoreUnknownKeys = true }
private const val gib = 1_073_741_824L

private fun managedStacks(config: SwarmConfig) = setOf("infra", "edge") + config.sites.map { it.id }

/** Repository identity with Docker Hub's implicit registry and library namespace made explicit. */
internal fun swarmImageRepository(reference: String): String {
    val tagged = reference.substringBefore('@')
    val repository = if (tagged.substringAfterLast('/').contains(':')) tagged.substringBeforeLast(':') else tagged
    val first = repository.substringBefore('/')
    return when {
        '/' !in repository -> "docker.io/library/$repository"
        '.' !in first && ':' !in first && first != "localhost" -> "docker.io/$repository"
        else -> repository.removePrefix("index.docker.io/").let {
            if (repository.startsWith("index.docker.io/")) "docker.io/$it" else it
        }
    }
}

private fun referenceKey(reference: String): String = when {
    reference.startsWith("sha256:") -> reference
    '@' in reference -> "${swarmImageRepository(reference)}@${reference.substringAfter('@')}"
    else -> "${swarmImageRepository(reference)}:${reference.substringAfterLast('/').substringAfter(':', "latest")}"
}

private fun SwarmStoredImage.keys(): Set<String> = buildSet {
    add(id)
    references.forEach { reference ->
        add(referenceKey(reference))
        // Containerd can store the tag only as tag@digest; an incoming tag still protects it.
        if (':' in reference.substringBefore('@').substringAfterLast('/')) add(referenceKey(reference.substringBefore('@')))
    }
}

/**
 * Plans deletion only within repositories used by managed stacks. Keep configured/current/rollback
 * pins, every non-terminal container, and every container from another workload. Shared image aliases
 * are treated as one object; an alias outside the managed repositories protects the entire image.
 */
internal fun planSwarmImageCleanup(
    images: List<SwarmStoredImage>,
    containers: List<SwarmStoredContainer>,
    stacks: Set<String>,
    protectedReferences: Set<String>,
    ownedReferences: Set<String>,
): SwarmImageCleanupPlan {
    val owned = ownedReferences.map(::swarmImageRepository).toSet()
    fun SwarmStoredContainer.retired() = state in setOf("exited", "dead") && service?.substringBefore('_') in stacks
    val protected = protectedReferences.map(::referenceKey).toMutableSet()
    containers.filterNot { it.retired() }.forEach {
        protected += it.image
        protected += referenceKey(it.reference)
    }
    val candidates = images.groupBy { it.id }.map { (id, aliases) ->
        SwarmStoredImage(id, aliases.flatMap { it.references }.distinct())
    }.filter { image ->
        image.references.isNotEmpty() && image.references.all { swarmImageRepository(it) in owned } &&
            image.keys().none { it in protected }
    }
    val retired = containers.filter { container ->
        container.retired() && candidates.any { it.id == container.image || referenceKey(container.reference) in it.keys() }
    }
    return SwarmImageCleanupPlan(retired, candidates)
}

private fun <T> readObjects(target: String, command: String, serializer: KSerializer<T>): List<T> =
    script(target, "set -euo pipefail\n$command", true).lineSequence().filter(String::isNotBlank)
        .map { storageJson.decodeFromString(serializer, it) }.toList()

internal fun readSwarmServices(config: SwarmConfig): List<SwarmStoredService> = readObjects(
    config.manager.ssh,
    """sudo -n docker service ls -q | xargs -r sudo -n docker service inspect --format '{"name":{{json .Spec.Name}},"stack":{{json (index .Spec.Labels "com.docker.stack.namespace")}},"image":{{json .Spec.TaskTemplate.ContainerSpec.Image}},"previous":{{if .PreviousSpec}}{{json .PreviousSpec.TaskTemplate.ContainerSpec.Image}}{{else}}null{{end}}}'""",
    SwarmStoredService.serializer(),
)

private fun readNodeImages(target: String) = readObjects(target,
    """sudo -n docker image ls -aq --no-trunc | sort -u | xargs -r sudo -n docker image inspect --format '{"id":{{json .Id}},"tags":{{json .RepoTags}},"digests":{{json .RepoDigests}}}'""",
    SwarmStoredImage.serializer())

private fun readNodeContainers(target: String) = readObjects(target,
    """sudo -n docker container ls -aq --no-trunc | xargs -r sudo -n docker container inspect --format '{"id":{{json .Id}},"image":{{json .Image}},"reference":{{json .Config.Image}},"service":{{json (index .Config.Labels "com.docker.swarm.service.name")}},"state":{{json .State.Status}}}'""",
    SwarmStoredContainer.serializer())

private fun historyPath(config: SwarmConfig) = "${config.dataRoot}/image-retention/history.json"

private fun readHistory(config: SwarmConfig): SwarmImageHistory {
    val path = shell(historyPath(config))
    val json = script(config.manager.ssh, "set -euo pipefail\nif sudo -n test -e $path; then sudo -n cat $path; fi", true)
    return if (json.isBlank()) SwarmImageHistory() else storageJson.decodeFromString(SwarmImageHistory.serializer(), json)
}

/** Free bytes on each relevant filesystem; containerd may store its layers outside DockerRootDir. */
internal fun swarmDiskSpaceScript(config: SwarmConfig): String = """set -euo pipefail
docker_root=${'$'}(sudo -n docker info --format '{{.DockerRootDir}}')
for path in "${'$'}docker_root" /var/lib/containerd ${shell(config.dataRoot)}; do
  while ! sudo -n test -d "${'$'}path"; do path=${'$'}(dirname "${'$'}path"); done
  sudo -n df -Pk "${'$'}path" | awk 'NR == 2 {printf "%.0f %s\n", $4 * 1024, $6}'
done
"""

private fun nodeFreeSpace(config: SwarmConfig, target: String): Long {
    val lines = script(target, swarmDiskSpaceScript(config), true).lines().filter(String::isNotBlank)
    require(lines.size == 3) { "Cannot read Docker, containerd and deployment disk space on $target" }
    return lines.minOf { line ->
        line.trim().substringBefore(' ').toLongOrNull() ?: error("Cannot read disk space on $target: $line")
    }
}

@Suppress("UNCHECKED_CAST")
private fun configuredImages(config: SwarmConfig): Set<String> = buildSet {
    // Include images used while installing files (for example, docker run to determine a volume owner).
    addAll(config.images.values)
    val stacks = listOf(infraStack(config), edgeStack(config)) + config.sites.map { siteStack(config, it) }
    stacks.forEach { stack ->
        (stack.getValue("services") as Map<String, Map<String, Any>>).values.forEach { add(it.getValue("image") as String) }
    }
}

private fun List<SwarmStoredService>.images() = flatMap { listOfNotNull(it.image, it.previous) }.toSet()
private fun List<SwarmStoredService>.currentImages() = map { it.image }.toSet()

/** Checks capacity before any pull; low-space recovery keeps the existing deployment and rollback pins. */
internal fun prepareSwarmStorage(config: SwarmConfig): SwarmStorageState {
    val state = SwarmStorageState(readSwarmServices(config), readHistory(config))
    val owned = configuredImages(config) + state.services.filter { it.stack in managedStacks(config) }.images() +
        state.history.current + state.history.previous
    val pins = owned + state.services.images()
    for (target in listOf(config.manager.ssh) + config.workers.map { it.ssh }) {
        var free = nodeFreeSpace(config, target)
        println("Storage on $target: ${free / gib} GiB available (minimum ${config.imageRetention.minimumFreeGiB} GiB)")
        if (free < config.imageRetention.minimumFreeGiB * gib && config.imageRetention.enabled) {
            cleanupSwarmNode(config, target, pins, owned)
            free = nodeFreeSpace(config, target)
        }
        require(free >= config.imageRetention.minimumFreeGiB * gib) {
            "Insufficient disk space on $target: ${free / gib} GiB available; need at least " +
                "${config.imageRetention.minimumFreeGiB} GiB before pulling images. Active and rollback images were preserved."
        }
    }
    return state
}

/** Advances one rollback generation only when the healthy deployment's images actually change. */
internal fun nextSwarmImageHistory(before: SwarmStorageState, current: Set<String>, stacks: Set<String>): SwarmImageHistory {
    val starting = before.services.filter { it.stack in stacks }
    val previous = when {
        before.history.current.isNotEmpty() && before.history.current != current -> before.history.current
        before.history.current.isNotEmpty() -> before.history.previous
        starting.currentImages().isNotEmpty() && starting.currentImages() != current -> starting.currentImages()
        before.history.previous.isNotEmpty() -> before.history.previous
        else -> starting.mapNotNull { it.previous }.toSet()
    }
    return SwarmImageHistory(current, previous)
}

/** Removes omitted services only after their replacements and public endpoints have passed readiness. */
internal fun finishSwarmStorage(config: SwarmConfig, before: SwarmStorageState) {
    val stacks = managedStacks(config)
    val expected = expectedServices(config)
    val services = readSwarmServices(config)
    val obsolete = services.filter { it.stack in stacks && it.name !in expected }
    for (service in obsolete) {
        println("Removing retired Swarm service ${service.name}")
        script(config.manager.ssh, "sudo -n docker service rm ${shell(service.name)}")
    }
    val after = readSwarmServices(config)
    val history = nextSwarmImageHistory(before, after.filter { it.stack in stacks }.currentImages(), stacks)
    // Atomic replacement: an interrupted deployment must never leave a truncated rollback ledger.
    val directory = shell("${config.dataRoot}/image-retention")
    val path = shell(historyPath(config))
    val json = shell(storageJson.encodeToString(SwarmImageHistory.serializer(), history))
    script(config.manager.ssh, """set -euo pipefail
sudo -n install -d -m 0700 $directory
temporary=${'$'}(sudo -n mktemp $directory/history.XXXXXX)
trap 'sudo -n rm -f "${'$'}temporary"' EXIT
printf '%s\n' $json | sudo -n tee "${'$'}temporary" >/dev/null
sudo -n mv "${'$'}temporary" $path
""")
    if (!config.imageRetention.enabled) return
    val configured = configuredImages(config)
    val protected = configured + after.images() + history.current + history.previous
    val owned = configured + after.filter { it.stack in stacks }.images() + history.current + history.previous +
        before.services.filter { it.stack in stacks }.images() +
        before.history.current + before.history.previous + obsolete.images()
    for (target in listOf(config.manager.ssh) + config.workers.map { it.ssh }) {
        cleanupSwarmNode(config, target, protected, owned)
    }
}

/**
 * Uses explicit references and Docker's non-forced removal. Rechecks all services and containers before
 * deleting images so another workload that starts during cleanup gains protection as well.
 */
internal fun cleanupSwarmNode(config: SwarmConfig, target: String, protected: Set<String>, owned: Set<String>) {
    val freeBefore = nodeFreeSpace(config, target)
    var services = readSwarmServices(config)
    var plan = planSwarmImageCleanup(readNodeImages(target), readNodeContainers(target), managedStacks(config),
        protected + services.images(), owned)
    if (plan.containers.isNotEmpty()) {
        // No force, no -v: Docker refuses a container that became active after the inventory.
        script(target, "sudo -n docker container rm " + plan.containers.joinToString(" ") { shell(it.id) })
    }
    val removedContainers = plan.containers.size
    services = readSwarmServices(config)
    plan = planSwarmImageCleanup(readNodeImages(target), readNodeContainers(target), managedStacks(config),
        protected + services.images(), owned)
    // A newly exited container stays protected until the next deployment; only removed containers release images.
    val remaining = readNodeContainers(target)
    val deletable = plan.images.filter { image ->
        remaining.none { it.image == image.id || referenceKey(it.reference) in image.keys() }
    }
    if (deletable.isNotEmpty()) {
        script(target, buildString {
            appendLine("set -euo pipefail")
            for (image in deletable) for (reference in image.references) {
                appendLine("if image_id=\$(sudo -n docker image inspect --format '{{.Id}}' ${shell(reference)} 2>/dev/null); then")
                appendLine("  test \"\$image_id\" = ${shell(image.id)} || { echo 'Image reference changed during cleanup; stopping' >&2; exit 1; }")
                appendLine("  sudo -n docker image rm --no-prune ${shell(reference)}")
                appendLine("fi")
            }
        })
    }
    val remainingIds = readNodeImages(target).map { it.id }.toSet()
    require(deletable.none { it.id in remainingIds }) {
        "Image cleanup on $target left retired images behind: " + deletable.filter { it.id in remainingIds }.joinToString { it.id }
    }
    val freeAfter = nodeFreeSpace(config, target)
    println("Image cleanup on $target: removed ${deletable.size} retired images and $removedContainers stopped tasks; " +
        "${(freeAfter - freeBefore) / (1024 * 1024)} MiB reclaimed, ${freeAfter / gib} GiB available")
}
