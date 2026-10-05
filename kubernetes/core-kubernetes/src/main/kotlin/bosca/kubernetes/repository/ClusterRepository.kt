package bosca.kubernetes.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.kubernetes.model.Cluster
import bosca.serialization.UUID

/**
 * Persists [Cluster] rows in `kubernetes.cluster`. User-driven
 * mutations use optimistic locking (`version` column); a mismatch
 * returns null so the calling service can surface a typed conflict
 * error. **Observed-state updates** ([updateObservedState],
 * [markHealth]) intentionally do *not* bump `version` — the health
 * probe fires every 30s and bumping version would constantly race
 * the studio's edit dialog out of its optimistic-lock guard.
 *
 * Soft-delete is via the `deleted_at` column — `list` and `getById`
 * filter it out by default. The raw `getByIdIncludingDeleted` helper
 * is reserved for the audit / restore paths.
 */
@Repository
interface ClusterRepository {

    @Query("select * from kubernetes.cluster where id = :id and deleted_at is null")
    suspend fun getById(id: UUID): Cluster?

    @Query("select * from kubernetes.cluster where id = :id")
    suspend fun getByIdIncludingDeleted(id: UUID): Cluster?

    @Query("select * from kubernetes.cluster where name = :name and deleted_at is null")
    suspend fun getByName(name: String): Cluster?

    @Query("select * from kubernetes.cluster where deleted_at is null order by name")
    suspend fun list(): List<Cluster>

    @Query(
        """
        insert into kubernetes.cluster (id, name, provider, region, environment)
        values (:id, :name, :provider, :region, :environment)
        returning *
        """
    )
    suspend fun add(
        id: UUID,
        name: String,
        provider: String,
        region: String,
        environment: String,
    ): Cluster

    @Query(
        """
        update kubernetes.cluster
        set name = coalesce(:name, name),
            environment = coalesce(:environment, environment),
            modified_at = now(),
            version = version + 1
        where id = :id
          and version = :expectedVersion
          and deleted_at is null
        returning *
        """
    )
    suspend fun updateMetadata(
        id: UUID,
        name: String?,
        environment: String?,
        expectedVersion: Long,
    ): Cluster?

    @Query(
        """
        update kubernetes.cluster
        set server_version = :serverVersion,
            health = :health,
            nodes = :nodes,
            pods = :pods,
            last_seen_at = now(),
            modified_at = now()
        where id = :id and deleted_at is null
        returning *
        """
    )
    suspend fun updateObservedState(
        id: UUID,
        serverVersion: String,
        health: String,
        nodes: Int,
        pods: Int,
    ): Cluster?

    @Query(
        """
        update kubernetes.cluster
        set health = :health,
            modified_at = now()
        where id = :id and deleted_at is null
        returning *
        """
    )
    suspend fun markHealth(id: UUID, health: String): Cluster?

    @Query(
        """
        update kubernetes.cluster
        set deleted_at = now(),
            modified_at = now(),
            version = version + 1
        where id = :id and deleted_at is null
        returning *
        """
    )
    suspend fun softDelete(id: UUID): Cluster?
}
