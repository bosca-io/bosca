package bosca.gateway.service

import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayHealthStatus
import bosca.gateway.model.GatewayInput
import bosca.security.model.PermissionService
import bosca.serialization.UUID

interface GatewayService : PermissionService<Gateway, UUID> {
    suspend fun listAll(): List<Gateway>
    suspend fun getById(id: UUID): Gateway?

    /** Batched lookup for resolving the parent Gateway of many routes at once. */
    suspend fun getByIds(ids: List<UUID>): List<Gateway>
    suspend fun getByName(name: String): Gateway?
    suspend fun create(input: GatewayInput): Gateway
    suspend fun update(id: UUID, input: GatewayInput, expectedVersion: Long): Gateway
    suspend fun toggleEnabled(id: UUID, enabled: Boolean, expectedVersion: Long): Gateway
    suspend fun delete(id: UUID, expectedVersion: Long): Gateway

    /**
     * Records a proxy-reported health transition. Returns the updated
     * [Gateway] when the status actually flipped, or null when the
     * upstream is already in the reported state (i.e., a duplicate
     * report — common with multiple proxy replicas).
     */
    suspend fun updateHealth(id: UUID, status: GatewayHealthStatus, reason: String?): Gateway?
}
