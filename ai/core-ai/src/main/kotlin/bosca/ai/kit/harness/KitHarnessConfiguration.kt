package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Deployment-selected runtime profile and limits, pinned for an active generation. */
@Serializable
data class KitHarnessConfiguration(
    val profile: String,
    val endpointTemplate: String,
    val boscaEndpoint: String,
    /** Explicit advertised ACP authentication method; null leaves provider authentication to the harness environment. */
    val authenticationMethod: String? = null,
    val idleSeconds: Long = 900,
    val cancellationSeconds: Long = 30,
    val credentialLifetimeSeconds: Long = 86400,
    val maxArchiveBytes: Long = 8L * 1024 * 1024 * 1024,
    val scopes: List<String>,
    /** Exact digest-pinned worker image reference in the admitted workload. */
    val image: String,
    /** Provider image identity: kubelet imageID, or the running Swarm task's pinned image digest. */
    val imageId: String,
    /** Versions required before any checkpoint restoration or native work. */
    val versions: RuntimeVersions,
    val contextCharacters: Int = 32000,
    val mcpExecutables: Map<String, List<String>> = emptyMap(),
    val mcpSecretEnvironment: List<String> = emptyList(),
    /** Total activation time from durable dispatch through native session readiness, including retries. */
    val startupSeconds: Long = 900,
    /** Total receipt bytes retained by one generation; retirement starts between turns at half capacity. */
    val maxJournalBytes: Long = 64L * 1024 * 1024,
    /** Maximum accepted turn identities retained by one generation, including cancelled turns. */
    val maxJournalTurns: Int = 256,
    /** Budget for a runtime control request or opening an ACP transport. */
    val connectionSeconds: Long = 10,
    /** Per-attempt budget for ACP negotiation, MCP preparation, native session setup and model controls. */
    val readinessSeconds: Long = 600,
    /** Per-attempt budget for downloading, verifying and installing a saved checkpoint. */
    val restoreSeconds: Long = 600,
    /** Decision lifetime recorded once for each native permission request. */
    val approvalSeconds: Long = 86400,
    /** Wall-clock prompt budget, starting at durable submission and including approval waits. */
    val promptSeconds: Long = 86400,
    /** Maximum generation lifetime, including dispatch waits; cannot exceed its scoped credential lifetime. */
    val hardRuntimeSeconds: Long = 86400,
    /** Optional cold-state retention; null retains saved work until conversation deletion. */
    val maxArchiveAgeSeconds: Long? = null,
    /** Per-attempt budget for archive creation, transfer, verification and commitment. */
    val archiveSeconds: Long = 600,
    val infrastructure: KitInfrastructure = KitInfrastructure.KUBERNETES,
    /** Egress proxy origin reachable from the workload; null leaves outbound access to the deployment's network. */
    val egressProxy: String? = null,
    /** Deployment-approved `host[:port]` destinations, such as the harness's model provider API. */
    val egressHosts: List<String> = emptyList(),
)
