package bosca.gateway.service

import bosca.db.transaction
import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayConflictException
import bosca.gateway.model.GatewayHealthStatus
import bosca.gateway.model.GatewayInUseException
import bosca.gateway.model.GatewayInput
import bosca.gateway.model.GatewayNotFoundException
import bosca.gateway.model.GatewayOptimisticLockFailedException
import bosca.gateway.model.isUniqueViolation
import bosca.gateway.repository.GatewayConfigVersionRepository
import bosca.gateway.repository.GatewayPermissionRepository
import bosca.gateway.repository.GatewayRepository
import bosca.gateway.repository.GatewayRouteRepository
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class GatewayServiceImpl(
    private val repository: GatewayRepository,
    private val routeRepository: GatewayRouteRepository,
    private val configVersionRepository: GatewayConfigVersionRepository,
    private val permissionRepository: GatewayPermissionRepository,
) : GatewayService {

    override suspend fun getPermissions(entity: Gateway): List<EntityPermission> =
        permissionRepository.getByGatewayId(entity.id)

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val permissions = permissionRepository.getByGatewayIds(batch.keys.toList())
        val grouped = permissions.groupBy { it.entityId }
        for (key in batch.keys) {
            batch.setData(key, grouped[key] ?: emptyList())
        }
    }

    override suspend fun listAll(): List<Gateway> = repository.getAll()

    override suspend fun getById(id: UUID): Gateway? = repository.getById(id)

    override suspend fun getByIds(ids: List<UUID>): List<Gateway> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun getByName(name: String): Gateway? = repository.getByName(name)

    override suspend fun create(input: GatewayInput): Gateway = transaction {
        // Pre-check is best-effort to surface the typed conflict on the
        // common (non-racing) path. The unique constraint on `name` is
        // the source of truth — see [remapUniqueViolation] for the
        // race-safe fallback when two concurrent creates collide.
        if (repository.getByName(input.name) != null) {
            throw GatewayConflictException("Gateway", input.name)
        }
        val gateway = remapUniqueViolation(input.name) {
            repository.add(
                Gateway(
                    name = input.name,
                    url = input.url,
                    healthCheckPath = input.healthCheckPath,
                    healthCheckIntervalSecs = input.healthCheckIntervalSecs,
                    connectTimeoutSecs = input.connectTimeoutSecs,
                    requestTimeoutSecs = input.requestTimeoutSecs,
                    poolMaxIdle = input.poolMaxIdle,
                    poolIdleTimeoutSecs = input.poolIdleTimeoutSecs,
                )
            )
        }
        configVersionRepository.bump()
        gateway
    }

    override suspend fun update(id: UUID, input: GatewayInput, expectedVersion: Long): Gateway = transaction {
        val existing = repository.getByName(input.name)
        if (existing != null && existing.id != id) {
            throw GatewayConflictException("Gateway", input.name)
        }
        val updated = remapUniqueViolation(input.name) {
            repository.update(
                id = id,
                name = input.name,
                url = input.url,
                healthCheckPath = input.healthCheckPath,
                healthCheckIntervalSecs = input.healthCheckIntervalSecs,
                connectTimeoutSecs = input.connectTimeoutSecs,
                requestTimeoutSecs = input.requestTimeoutSecs,
                poolMaxIdle = input.poolMaxIdle,
                poolIdleTimeoutSecs = input.poolIdleTimeoutSecs,
                expectedVersion = expectedVersion,
            )
        } ?: throw rowMissingOrStale(id)
        configVersionRepository.bump()
        updated
    }

    override suspend fun toggleEnabled(id: UUID, enabled: Boolean, expectedVersion: Long): Gateway = transaction {
        val updated = repository.toggleEnabled(id, enabled, expectedVersion)
            ?: throw rowMissingOrStale(id)
        configVersionRepository.bump()
        updated
    }

    override suspend fun delete(id: UUID, expectedVersion: Long): Gateway = transaction {
        val routeCount = routeRepository.countByGatewayId(id)
        if (routeCount > 0) {
            throw GatewayInUseException("Gateway", id, "$routeCount active route(s) reference it")
        }
        val deleted = repository.delete(id, expectedVersion)
            ?: throw rowMissingOrStale(id)
        configVersionRepository.bump()
        deleted
    }

    /**
     * Records a proxy-reported health transition. The repository
     * filters duplicate same-status reports at the SQL layer, so a null
     * return here means "no transition" and is not an error. Health
     * updates deliberately do NOT bump `configVersionRepository` —
     * proxies re-pull config based on its version, and a health
     * change doesn't invalidate any routing decisions the proxy made.
     */
    override suspend fun updateHealth(
        id: UUID,
        status: GatewayHealthStatus,
        reason: String?,
    ): Gateway? = transaction {
        val current = repository.getById(id)
            ?: throw GatewayNotFoundException("Gateway", id.toString())
        if (current.healthStatus == status) {
            // Idempotent no-op — saves the repository round-trip and
            // gives the REST handler a 200-with-no-body response shape
            // when the proxy is reporting an unchanged status.
            return@transaction null
        }
        repository.updateHealth(id, status, reason)
    }

    /**
     * The repository returns null both when the row is missing and when the
     * version mismatched. We distinguish the two by checking whether the row
     * is currently present at all so callers get the right typed error.
     */
    private suspend fun rowMissingOrStale(id: UUID): RuntimeException =
        if (repository.getById(id) == null) GatewayNotFoundException("Gateway", id.toString())
        else GatewayOptimisticLockFailedException("Gateway", id)

    /**
     * Wraps a repository write so a Postgres unique-constraint violation
     * (SQLSTATE `23505`, raced past the pre-check) surfaces as a typed
     * [GatewayConflictException] rather than leaking the driver's
     * [java.sql.SQLException] through the GraphQL layer.
     */
    private inline fun <T> remapUniqueViolation(name: String, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            if (isUniqueViolation(e)) throw GatewayConflictException("Gateway", name)
            throw e
        }
}
