package bosca.workops.service

import bosca.security.model.PermissionService
import bosca.serialization.UUID
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.PortfolioInput

/**
 * Service surface for the [Portfolio] hierarchy root (R1). Every
 * mutation runs inside a `transaction` so the row write and its
 * companion `task_history` writes commit atomically — Phase 2 has
 * no portfolio-level history rows, but the contract is established
 * here so Phases 3+ can hang automation, audit, and notification
 * fan-out off the same boundary.
 */
interface PortfolioService : PermissionService<Portfolio, UUID> {

    suspend fun list(offset: Long, limit: Int): List<Portfolio>

    suspend fun getById(id: UUID): Portfolio?

    suspend fun getByKey(key: String): Portfolio?

    suspend fun getByIds(ids: List<UUID>): List<Portfolio>

    suspend fun create(input: PortfolioInput): Portfolio

    suspend fun update(id: UUID, input: PortfolioInput, expectedVersion: Long): Portfolio

    suspend fun archive(id: UUID, expectedVersion: Long): Portfolio

    suspend fun unarchive(id: UUID, expectedVersion: Long): Portfolio
}
