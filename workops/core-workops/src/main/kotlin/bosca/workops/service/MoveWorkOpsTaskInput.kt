package bosca.workops.service

import bosca.serialization.UUID

/**
 * Inputs for a `moveTaskToProject` call. R26 acceptance:
 *
 *  - `targetProjectId` — the project the task is moving into.
 *  - `statusMapping` — `Map<sourceStatusId, targetStatusId>`.
 *    The current status must be in the mapping; otherwise the
 *    move is rejected.
 *  - `fieldMapping` — `Map<sourceFieldKey, targetFieldKey>` for
 *    custom fields. Unmapped fields drop on the floor; required
 *    fields without a mapping reject the move.
 */
data class MoveWorkOpsTaskInput(
    val targetProjectId: UUID,
    val statusMapping: Map<UUID, UUID>,
    val fieldMapping: Map<String, String> = emptyMap(),
)
