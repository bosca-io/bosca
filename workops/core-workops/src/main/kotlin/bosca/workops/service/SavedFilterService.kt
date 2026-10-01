package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.bql.SavedFilter
import bosca.workops.model.bql.SavedFilterInput

/**
 * R10's saved-filter service. Phase 6 ships owner-only saving plus
 * the parse-on-write contract that rejects malformed queries before
 * they hit the table — every persisted filter is guaranteed to be
 * a runnable BQL plan, so the planner never has to special-case
 * "the saved filter parses but…" failure modes at search time.
 *
 * The visibility / role-shared / project-shared / public flavors
 * (R10 acceptance criteria) hook in alongside the Phase 7
 * permission scheme, when the broader visibility model is wired.
 */
interface SavedFilterService : Service {

    suspend fun listForOwner(ownerProfileId: UUID, offset: Long, limit: Int): List<SavedFilter>

    suspend fun getById(id: UUID): SavedFilter?

    suspend fun getByIds(ids: List<UUID>): List<SavedFilter>

    suspend fun create(ownerProfileId: UUID, input: SavedFilterInput): SavedFilter

    suspend fun update(id: UUID, input: SavedFilterInput, expectedVersion: Long): SavedFilter

    suspend fun delete(id: UUID)
}
