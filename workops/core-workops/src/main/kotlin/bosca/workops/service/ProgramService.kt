package bosca.workops.service

import bosca.security.model.PermissionService
import bosca.serialization.UUID
import bosca.workops.model.project.Program
import bosca.workops.model.project.ProgramInput

/**
 * Service surface for [Program] (R1). WorkOpsPrograms live inside portfolios,
 * are identified by a `(portfolioId, key)` pair, and inherit
 * read-only-when-parent-archived semantics from R1's transitive
 * archival rule: a program belonging to an archived portfolio is
 * itself read-only at the service layer, but its `archivedAt`
 * timestamp is not auto-set so an admin can later un-archive the
 * portfolio without an avalanche of cascade un-archival surprises.
 */
interface ProgramService : PermissionService<Program, UUID> {

    suspend fun listAll(): List<Program>

    suspend fun listByPortfolio(portfolioId: UUID, offset: Long, limit: Int): List<Program>

    suspend fun getById(id: UUID): Program?

    suspend fun getByKey(portfolioId: UUID, key: String): Program?

    suspend fun getByIds(ids: List<UUID>): List<Program>

    suspend fun create(input: ProgramInput): Program

    suspend fun update(id: UUID, input: ProgramInput, expectedVersion: Long): Program

    suspend fun archive(id: UUID, expectedVersion: Long): Program

    suspend fun unarchive(id: UUID, expectedVersion: Long): Program
}
