package bosca.kubernetes.repository

import bosca.db.annotation.ColumnName
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Persists the encrypted kubeconfig blob keyed on cluster id. The
 * (nonce, data) pair is the output of
 * [bosca.security.encryption.EncryptionService.encrypt] — callers must
 * not access this repository directly; route through
 * [bosca.kubernetes.service.ClusterCredentialService] so audit hooks
 * fire and the encryption-service API stays the only path that
 * touches plaintext kubeconfig bytes.
 *
 * The credential travels as a single [ClusterCredential] model rather
 * than as `(clusterId, nonce, data)` separately because KSP's
 * `@Query` processor requires every parameter to be either primitive
 * or a `@Serializable` model, and `ByteArray` is neither.
 */
@Repository
interface ClusterCredentialRepository {

    @Query(
        "select cluster_id, nonce, data from kubernetes.cluster_credential where cluster_id = :clusterId"
    )
    suspend fun get(clusterId: UUID): ClusterCredential?

    @Query(
        """
        insert into kubernetes.cluster_credential (cluster_id, nonce, data)
        values (:clusterId, :nonce, :data)
        on conflict (cluster_id) do update set
            nonce = excluded.nonce,
            data = excluded.data,
            modified_at = now()
        """
    )
    suspend fun upsert(credential: ClusterCredential)

    @Query("delete from kubernetes.cluster_credential where cluster_id = :clusterId")
    suspend fun delete(clusterId: UUID)
}

/**
 * Storage projection of `kubernetes.cluster_credential`. `@Serializable`
 * makes the type eligible for KSP's `@Query`-with-model-parameter path;
 * the column-name mapping translates `clusterId` ↔ `cluster_id`.
 */
@Serializable
data class ClusterCredential(
    @ColumnName("cluster_id")
    @Contextual
    val clusterId: UUID,
    val nonce: ByteArray,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClusterCredential) return false
        return clusterId == other.clusterId
            && nonce.contentEquals(other.nonce)
            && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = clusterId.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
