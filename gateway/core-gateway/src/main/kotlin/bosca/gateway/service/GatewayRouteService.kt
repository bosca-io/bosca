package bosca.gateway.service

import bosca.gateway.model.GatewayRoute
import bosca.gateway.model.GatewayRouteInput
import bosca.serialization.UUID
import bosca.service.Service

interface GatewayRouteService : Service {
    suspend fun listAll(): List<GatewayRoute>
    suspend fun getById(id: UUID): GatewayRoute?
    suspend fun listByGatewayId(gatewayId: UUID): List<GatewayRoute>
    suspend fun create(input: GatewayRouteInput): GatewayRoute
    suspend fun update(id: UUID, input: GatewayRouteInput, expectedVersion: Long): GatewayRoute
    suspend fun toggleEnabled(id: UUID, enabled: Boolean, expectedVersion: Long): GatewayRoute
    suspend fun delete(id: UUID, expectedVersion: Long): GatewayRoute
    suspend fun countByGatewayId(gatewayId: UUID): Long
}
