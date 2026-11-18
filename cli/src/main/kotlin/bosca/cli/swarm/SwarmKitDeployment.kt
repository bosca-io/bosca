package bosca.cli.swarm

import bosca.ai.kit.harness.KitHarnessConfiguration
import bosca.ai.kit.harness.KitHarnessDeployment
import bosca.ai.kit.harness.KitInfrastructure
import bosca.ai.kit.harness.RuntimeVersions
import bosca.cli.images.ImageInspection
import bosca.cli.images.ImageRegistry
import bosca.cli.swarm.runtime.SwarmRuntimeRegistryAuth
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.security.MessageDigest

/** Resolve published image metadata once while preparing a reviewable deployment bundle. */
internal suspend fun resolveSwarmKit(config: SwarmConfig, inspect: suspend (String) -> ImageInspection = { reference ->
    val authority = URI("https://$reference").rawAuthority
    val auth = registryAuths(config).firstOrNull { it.server == authority }
    val password = auth?.let(::registryPassword).orEmpty()
    require(auth == null || password.isNotBlank()) { "Configure the image registry credential" }
    ImageRegistry(auth?.username.orEmpty(), password).inspect(if (config.registrySchemes[authority] == "http") "http://$reference" else reference)
}): SwarmConfig = config.copy(sites = config.sites.map { site ->
    if (!site.kit.enabled) site else {
        val kit = site.kit
        require(kit.harness in setOf("codex", "claude")) { "Kit harness must be codex or claude" }
        require(site.secrets.kitProvider.isNotBlank()) { "Set sites[${site.id}].secrets.kitProvider" }
        require(kit.memoryBytes > kit.stateBytes && kit.stateBytes > 0 && kit.idleSeconds > 0) { "Invalid Kit runtime limits" }
        val image = inspect(kit.image.ifBlank { config.images.getValue("kit-${kit.harness}-runtime") })
        require(image.os == "linux" && image.architecture in setOf("amd64", "arm64")) { "Kit runtime requires a supported Linux image" }
        val versions = swarmJson.decodeFromString(RuntimeVersions.serializer(), requireNotNull(image.labels["io.bosca.kit.versions"]) { "Kit image has no deployment metadata; use a published Kit runtime image" })
        require(versions.statePolicy.substringBefore('-') == kit.harness && image.reference.substringAfterLast('@').matches(Regex("sha256:[a-f0-9]{64}")) &&
            image.id.matches(Regex("sha256:[a-f0-9]{64}"))) { "Kit image metadata does not match its harness" }
        site.copy(kit = kit.copy(resolved = KitHarnessConfiguration("kit-${kit.harness}", "http://{service}:8080", "http://${site.id}-server:8080/graphql",
            authenticationMethod = if (kit.harness == "codex") "api-key" else null, idleSeconds = kit.idleSeconds,
            scopes = kit.scopes, image = image.reference, imageId = image.reference.substringAfterLast('@'), versions = versions, infrastructure = KitInfrastructure.SWARM,
            egressProxy = KIT_EGRESS_PROXY, egressHosts = kitProviderEgressHosts(kit.harness)),
            platform = "${image.os}/${image.architecture}"))
    }
})

/**
 * The site's internal overlay for Kit runtimes. Runtimes join only this network; the site's server and runner also
 * join it so a runtime reaches Bosca's API and the Runner reaches the runtime, while infrastructure stays unreachable.
 * The network is created with `--internal`, so it has no route outside the cluster; the egress proxy is the
 * runtime's only path to other destinations.
 */
internal fun kitRuntimeNetwork(site: SwarmSite) = "${site.id}-kit-runtime"

/** The site's routable overlay that carries only the Kit egress proxy's outbound connections. */
internal fun kitEgressNetwork(site: SwarmSite) = "${site.id}-kit-egress"

/** Site services that a Kit runtime exchanges traffic with. */
internal val KIT_RUNTIME_PEERS = setOf("server", "runner")

/** Stack service name, and its alias on both Kit overlays, of the site's egress proxy. */
internal const val KIT_EGRESS_SERVICE = "kit-egress"

internal const val KIT_EGRESS_PORT = 3128

/** Proxy origin that runtimes on the site's Kit runtime overlay use. */
internal const val KIT_EGRESS_PROXY = "http://$KIT_EGRESS_SERVICE:$KIT_EGRESS_PORT"

/** Model provider API that the harness family calls with its provider credential. */
internal fun kitProviderEgressHosts(harness: String): List<String> = when (harness) {
    "codex" -> listOf("api.openai.com:443")
    "claude" -> listOf("api.anthropic.com:443")
    else -> throw IllegalArgumentException("Kit harness must be codex or claude")
}

/**
 * The site's public HTTPS entry points, which the egress proxy always permits for an authenticated runtime.
 * The `www` host is served only with a root website image, as in the Caddyfile.
 */
internal fun kitEgressAllow(site: SwarmSite): List<String> {
    val hosts = site.hosts
    return (if (site.rootImage.isBlank()) hosts.all - hosts.www else hosts.all).distinct().map { "$it:443" }
}

/**
 * Manager commands that create the site's Kit overlays. An existing runtime overlay must already be internal;
 * Docker cannot change that property in place, so deployment stops and names the network to recreate.
 */
internal fun kitNetworkCommands(site: SwarmSite): List<String> {
    val runtime = kitRuntimeNetwork(site)
    val egress = kitEgressNetwork(site)
    return listOf(
        """if internal=$(sudo -n docker network inspect --format '{{.Internal}}' $runtime 2>/dev/null); then
  if [ "${'$'}internal" != true ]; then
    echo "Docker network $runtime is not internal. Remove the ${site.id} stack (docker stack rm ${site.id}) and its Kit runtime services (label bosca.kit.kind=service), then run docker network rm $runtime and deploy again." >&2
    exit 1
  fi
else
  sudo -n docker network create --driver overlay --internal $runtime
fi""",
        "sudo -n docker network inspect $egress >/dev/null 2>&1 || sudo -n docker network create --driver overlay $egress",
    )
}

internal fun kitDeployment(site: SwarmSite) = KitHarnessDeployment(listOf(requireNotNull(site.kit.resolved) { "Resolve the enabled Kit runtime image before rendering" }),
    site.kit.maxRuntimes, site.kit.maxRuntimesPerPrincipal)

internal fun kitProviderSecret(site: SwarmSite): String = "${site.id}-kit-provider-" +
    MessageDigest.getInstance("SHA-256").digest(site.secrets.kitProvider.toByteArray()).toHexString().take(16)

internal fun kitRegistryAuthentication(config: SwarmConfig, site: SwarmSite): String? {
    val image = requireNotNull(site.kit.resolved)
    val authority = image.image.substringBefore('/')
    val auth = registryAuths(config).firstOrNull { it.server == authority } ?: return null
    val password = registryPassword(auth)
    require(password.isNotBlank()) { "Configure the image registry credential" }
    return swarmJson.encodeToString(SwarmRuntimeRegistryAuth.serializer(), SwarmRuntimeRegistryAuth(auth.username, password, authority))
}

internal fun kitRegistrySecret(site: SwarmSite, authentication: String): String = "${site.id}-kit-registry-" +
    MessageDigest.getInstance("SHA-256").digest(authentication.toByteArray()).toHexString().take(16)

internal fun kitControllerConfiguration(config: SwarmConfig, site: SwarmSite): String {
    val runtime = requireNotNull(site.kit.resolved)
    val kit = site.kit
    val platform = requireNotNull(kit.platform) { "Resolve the enabled Kit runtime platform before rendering" }.split('/')
    val container = mapOf(
        "Image" to runtime.image, "User" to "1000:1000", "ReadOnly" to true, "Init" to false, "Dir" to "/state",
        "Args" to listOf("--root", "/state/session", "--private-pid-namespace"), "StopGracePeriod" to 60000000000L,
        "CapabilityDrop" to listOf("ALL"), "Privileges" to mapOf("NoNewPrivileges" to true),
        "Env" to listOf("HOME=/state/session/home", "TMPDIR=/state/session/scratch", "CODEX_HOME=/state/session/harness",
            "CLAUDE_CONFIG_DIR=/state/session/harness", "CLAUDE_CODE_TMPDIR=/state/session/scratch"),
        "Mounts" to listOf(mapOf("Type" to "tmpfs", "Target" to "/state", "TmpfsOptions" to mapOf("SizeBytes" to kit.stateBytes, "Mode" to 1023, "Options" to listOf(listOf("exec"))))),
    )
    val service = mapOf("Mode" to mapOf("Replicated" to mapOf("Replicas" to 1)), "EndpointSpec" to mapOf("Mode" to "vip"),
        "TaskTemplate" to mapOf("ContainerSpec" to container, "Resources" to mapOf("Limits" to mapOf("NanoCPUs" to 1000000000, "MemoryBytes" to kit.memoryBytes, "Pids" to 512)),
            "RestartPolicy" to mapOf("Condition" to "none"), "Placement" to mapOf("Constraints" to listOf("node.platform.os==linux", "node.labels.bosca_data!=true"),
                "Platforms" to listOf(mapOf("OS" to platform[0], "Architecture" to platform[1]))),
            "Networks" to listOf(mapOf("Target" to kitRuntimeNetwork(site)))))
    val provider = if (kit.harness == "codex") "OPENAI_API_KEY" else "ANTHROPIC_API_KEY"
    val profile = mapOf("secretEnvironment" to mapOf(provider to kitProviderSecret(site)), "service" to service) +
        if (kitRegistryAuthentication(config, site) != null) mapOf("registryAuthSecret" to "kit-registry") else emptyMap()
    return document(mapOf("profiles" to mapOf(runtime.profile to profile)))
}
