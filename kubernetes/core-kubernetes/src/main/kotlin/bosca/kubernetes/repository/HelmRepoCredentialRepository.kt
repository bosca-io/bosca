package bosca.kubernetes.repository

import bosca.db.annotation.ColumnName
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Persists the encrypted HTTP Basic credential for a Helm repository.
 * Plaintext access is restricted to [bosca.kubernetes.service.HelmRepoCredentialService].
 */
@Repository
interface HelmRepoCredentialRepository {

    @Query("select repo_name, id, nonce, data from kubernetes.helm_repo_credential where repo_name = :repoName")
    suspend fun get(repoName: String): HelmRepoCredential?

    @Query(
        """
        insert into kubernetes.helm_repo_credential (repo_name, id, nonce, data)
        values (:repoName, :id, :nonce, :data)
        on conflict (repo_name) do update set
            id = excluded.id,
            nonce = excluded.nonce,
            data = excluded.data,
            modified_at = now()
        """,
    )
    suspend fun upsert(credential: HelmRepoCredential)

    @Query("delete from kubernetes.helm_repo_credential where repo_name = :repoName")
    suspend fun delete(repoName: String)
}

/** Storage projection of `kubernetes.helm_repo_credential`. */
@Serializable
data class HelmRepoCredential(
    @ColumnName("repo_name")
    val repoName: String,
    @Contextual
    val id: UUID,
    val nonce: ByteArray,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HelmRepoCredential) return false
        return repoName == other.repoName &&
            id == other.id &&
            nonce.contentEquals(other.nonce) &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = repoName.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
