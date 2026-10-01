package bosca.gateway.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayHealthStatus
import bosca.serialization.UUID

@Repository
interface GatewayRepository {

    @Query("select * from gateway.service where deleted_at is null order by name")
    suspend fun getAll(): List<Gateway>

    @Query("select * from gateway.service where id = :id and deleted_at is null")
    suspend fun getById(id: UUID): Gateway?

    @Query("select * from gateway.service where id = any(:ids) and deleted_at is null")
    suspend fun getByIds(ids: List<UUID>): List<Gateway>

    @Query("select * from gateway.service where name = :name and deleted_at is null")
    suspend fun getByName(name: String): Gateway?

    @Query("select * from gateway.service where enabled = true and deleted_at is null order by name")
    suspend fun getEnabled(): List<Gateway>

    @Query(
        """
        insert into gateway.service
            (name, url, health_check_path, health_check_interval_secs,
             connect_timeout_secs, request_timeout_secs, pool_max_idle, pool_idle_timeout_secs)
        values
            (:name, :url, :healthCheckPath, :healthCheckIntervalSecs,
             :connectTimeoutSecs, :requestTimeoutSecs, :poolMaxIdle, :poolIdleTimeoutSecs)
        returning *
        """
    )
    suspend fun add(service: Gateway): Gateway

    @Query(
        """
        update gateway.service
        set name = :name, url = :url, health_check_path = :healthCheckPath,
            health_check_interval_secs = :healthCheckIntervalSecs,
            connect_timeout_secs = :connectTimeoutSecs,
            request_timeout_secs = :requestTimeoutSecs,
            pool_max_idle = :poolMaxIdle, pool_idle_timeout_secs = :poolIdleTimeoutSecs,
            modified_at = now(), version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        name: String,
        url: String,
        healthCheckPath: String?,
        healthCheckIntervalSecs: Int,
        connectTimeoutSecs: Int,
        requestTimeoutSecs: Int,
        poolMaxIdle: Int,
        poolIdleTimeoutSecs: Int,
        expectedVersion: Long,
    ): Gateway?

    @Query(
        """
        update gateway.service
        set enabled = :enabled, modified_at = now(), version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun toggleEnabled(id: UUID, enabled: Boolean, expectedVersion: Long): Gateway?

    @Query(
        """
        update gateway.service
        set deleted_at = now(), modified_at = now(), version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun delete(id: UUID, expectedVersion: Long): Gateway?

    /**
     * Records a health-status transition reported by the proxy. The
     * `where status <> :status` guard makes this a no-op when the
     * proxy posts the same status twice (idempotent), and crucially
     * means `health_status_changed_at` is only updated on a real
     * transition — not on every duplicate POST. Returns the row when
     * the status actually flipped, null otherwise.
     *
     * This is NOT a normal mutation: it does NOT touch `version`
     * or `modified_at`. Health reports are out-of-band from operator
     * edits and shouldn't trigger optimistic-lock conflicts on the
     * config row.
     */
    @Query(
        """
        update gateway.service
        set health_status = (:status)::gateway.health_status,
            health_status_changed_at = now(),
            health_status_reason = :reason
        where id = :id
          and deleted_at is null
          and health_status <> (:status)::gateway.health_status
        returning *
        """
    )
    suspend fun updateHealth(id: UUID, status: GatewayHealthStatus, reason: String?): Gateway?
}
