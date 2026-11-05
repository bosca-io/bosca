package bosca.cli.swarm

import bosca.ai.kit.harness.KitHarnessConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Standard per-site Kit deployment; image identity is resolved from the published image. */
@Serializable
data class SwarmKit(
    val enabled: Boolean = false,
    val harness: String = "codex",
    /** Optional image override; blank follows the site's configured runtime release. */
    val image: String = "",
    val scopes: List<String> = listOf("content:view", "content:edit", "collections:view", "collections:edit", "search:read",
        "analytics:view", "analytics:execute", "analytics:sql", "scripts:view", "scripts:execute", "scripts:manage",
        "pipelines:view", "pipelines:edit", "pipelines:execute", "git:read"),
    val idleSeconds: Long = 900,
    val maxRuntimes: Int = 10,
    val maxRuntimesPerPrincipal: Int = 2,
    val memoryBytes: Long = 2147483648,
    val stateBytes: Long = 536870912,
    @Transient val resolved: KitHarnessConfiguration? = null,
    @Transient val platform: String? = null,
)
