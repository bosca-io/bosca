package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitHarnessConfiguration
import bosca.serialization.UUID

/** Infrastructure operations for a Kit generation; conversation and archive ownership stay in Kit. */
interface KitRuntimeProvider {
    /** Ensures one workload for [key], without placing credential values in its plaintext environment. */
    suspend fun ensure(configuration: KitHarnessConfiguration, key: String, environment: Map<String, String>, secrets: KitRuntimeSecrets): UUID

    /** Returns observed identity and connectivity for the opaque workload [reference]. */
    suspend fun status(configuration: KitHarnessConfiguration, reference: UUID): KitRuntimeExecution?

    /** Reports terminal reconciliation, including removal of owned workload resources when cancelled. */
    suspend fun isStopped(configuration: KitHarnessConfiguration, reference: UUID): Boolean

    /** Durably requests idempotent removal; callers reconcile [isStopped] before releasing ownership. */
    suspend fun stop(configuration: KitHarnessConfiguration, reference: UUID)
}
