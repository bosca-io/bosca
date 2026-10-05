package bosca.gateway.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.gateway.model.GatewayAuthMethod
import bosca.gateway.model.GatewayRoute
import bosca.serialization.UUID

@Repository
interface GatewayRouteRepository {

    @Query("select * from gateway.route where deleted_at is null order by sort_order, path_pattern")
    suspend fun getAll(): List<GatewayRoute>

    @Query("select * from gateway.route where id = :id and deleted_at is null")
    suspend fun getById(id: UUID): GatewayRoute?

    @Query("select * from gateway.route where gateway_id = :gatewayId and deleted_at is null order by sort_order")
    suspend fun getByGatewayId(gatewayId: UUID): List<GatewayRoute>

    @Query("select * from gateway.route where enabled = true and deleted_at is null order by sort_order")
    suspend fun getEnabled(): List<GatewayRoute>

    @Query(
        """
        insert into gateway.route
            (gateway_id, path_pattern, hosts, auth_method, strip_prefix,
             read_groups, write_groups, inject_headers, sort_order)
        values
            (:gatewayId, :pathPattern, :hosts, (:authMethod)::gateway.auth_method, :stripPrefix,
             :readGroups, :writeGroups, :injectHeaders::jsonb, :sortOrder)
        returning *
        """
    )
    suspend fun add(route: GatewayRoute): GatewayRoute

    @Query(
        """
        update gateway.route
        set gateway_id = :gatewayId, path_pattern = :pathPattern,
            hosts = string_to_array(:hosts, ',')::varchar[],
            auth_method = (:authMethod)::gateway.auth_method, strip_prefix = :stripPrefix,
            read_groups = string_to_array(:readGroups, ',')::varchar[],
            write_groups = string_to_array(:writeGroups, ',')::varchar[],
            inject_headers = :injectHeaders::jsonb,
            sort_order = :sortOrder,
            modified_at = now(), version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        gatewayId: UUID,
        pathPattern: String,
        hosts: String,
        authMethod: GatewayAuthMethod,
        stripPrefix: Boolean,
        readGroups: String,
        writeGroups: String,
        injectHeaders: String,
        sortOrder: Int,
        expectedVersion: Long,
    ): GatewayRoute?

    @Query(
        """
        update gateway.route
        set enabled = :enabled, modified_at = now(), version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun toggleEnabled(id: UUID, enabled: Boolean, expectedVersion: Long): GatewayRoute?

    @Query(
        """
        update gateway.route
        set deleted_at = now(), modified_at = now(), version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun delete(id: UUID, expectedVersion: Long): GatewayRoute?

    @Query("select count(*) from gateway.route where gateway_id = :gatewayId and deleted_at is null")
    suspend fun countByGatewayId(gatewayId: UUID): Long
}
