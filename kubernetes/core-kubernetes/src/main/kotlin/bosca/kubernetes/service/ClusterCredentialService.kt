package bosca.kubernetes.service

import bosca.serialization.UUID
import bosca.service.Service

/**
 * Reads and writes the encrypted kubeconfig blob for a registered
 * cluster. Plaintext kubeconfig bytes live in memory only for the
 * duration of [store] / [load] — the storage layer never sees them.
 *
 * Implementations encrypt every value before handing it to the storage layer.
 */
interface ClusterCredentialService : Service {

    /** Encrypts and persists `kubeconfig` for [clusterId], overwriting any existing credential. */
    suspend fun store(clusterId: UUID, kubeconfig: String)

    /** Returns the decrypted kubeconfig for [clusterId], or null if none has been stored. */
    suspend fun load(clusterId: UUID): String?

    /** Removes any stored credential for [clusterId]. Idempotent. */
    suspend fun delete(clusterId: UUID)
}
