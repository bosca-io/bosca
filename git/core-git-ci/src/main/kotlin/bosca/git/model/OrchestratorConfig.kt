package bosca.git.model

import kotlinx.serialization.Serializable

/**
 * Server-side configuration for an orchestrator agent. Stored as JSONB
 * in [PipelineAgent.providerConfig]. Contains provider credentials,
 * VM size mappings per runner label, and resource limits.
 *
 * The CLI orchestrator reads this configuration from the server on
 * startup and when provisioning VMs — it holds no provider config locally.
 */
@Serializable
data class OrchestratorConfig(
    val provider: String,
    val credentials: ProviderCredentials,
    val defaults: VmDefaults,
    val runnerProfiles: Map<String, VmProfile> = emptyMap(),
    val maxConcurrentVms: Int = 5,
    val maxJobTimeoutMinutes: Int = 60,
    val maxVmLifetimeMinutes: Int = 90,
    val alertSinks: List<AlertSink> = emptyList()
)

/**
 * Provider credential references. The actual API tokens are supplied
 * via the CLI's local config — never stored on the server. This only
 * holds metadata the server needs (like the self-destruct token scope
 * identifier, if applicable).
 */
@Serializable
data class ProviderCredentials(
    val selfDestructTokenScope: String? = null
)

/**
 * Default VM configuration applied when no runner-specific profile matches.
 */
@Serializable
data class VmDefaults(
    val region: String,
    val size: String,
    val image: String
)

/**
 * VM configuration for a specific runner label. When a pipeline job
 * specifies `runner: linux-8cpu`, the orchestrator looks up the
 * "linux-8cpu" profile to determine the VM size, region, and image.
 *
 * Any field left null falls back to [VmDefaults].
 */
@Serializable
data class VmProfile(
    val size: String? = null,
    val region: String? = null,
    val image: String? = null
)

/**
 * Notification sink for resource management alerts.
 */
@Serializable
data class AlertSink(
    val type: String,
    val url: String
)
