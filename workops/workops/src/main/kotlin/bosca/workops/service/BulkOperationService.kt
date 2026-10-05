package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.bulk.BulkOperation
import bosca.workops.model.bulk.BulkOperationHandle
import bosca.workops.model.bulk.BulkOperationState
import bosca.workops.model.task.UpdateTaskInput
import bosca.workops.repository.TaskRepository
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi

@ServiceImplementation
class BulkOperationServiceImpl(
    private val taskQueryService: TaskQueryService,
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
) : BulkOperationService {

    companion object {
        private val log = LoggerFactory.getLogger(BulkOperationServiceImpl::class.java)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        bql: String,
        operation: BulkOperation,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        dryRun: Boolean,
    ): BulkOperationHandle {
        val started = OffsetDateTime.now()
        val handleId = UUID.random()
        val pageSize = 500
        var totalMatched = 0L
        var processed = 0L
        var errors = 0L
        val errorDetails = mutableListOf<String>()
        var offset = 0L
        while (true) {
            val result = taskQueryService.search(bql, actingProfileId = actingProfileId, offset = offset, limit = pageSize)
            totalMatched += result.rows.size
            if (dryRun) {
                if (result.rows.size < pageSize) break
                offset += pageSize
                continue
            }
            for (row in result.rows) {
                try {
                    applyOne(row.id, operation, actingPrincipalId, actingProfileId)
                    processed++
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    val detail = "task ${row.key}: ${ex.message ?: ex.javaClass.simpleName}"
                    log.warn("Bulk operation failed for task {} ({}): {}", row.id, row.key, ex.message)
                    errorDetails.add(detail)
                    errors++
                }
            }
            if (result.rows.size < pageSize) break
            offset += pageSize
        }
        val state = if (dryRun) BulkOperationState.DRY_RUN
            else if (errors == 0L) BulkOperationState.COMPLETED
            else BulkOperationState.PARTIAL
        return BulkOperationHandle(
            id = handleId, total = totalMatched, processed = processed,
            errors = errors, errorDetails = errorDetails, state = state,
            startedAt = started, completedAt = OffsetDateTime.now(), dryRun = dryRun,
        )
    }

    private suspend fun applyOne(
        taskId: UUID,
        operation: BulkOperation,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ) {
        val task = taskRepository.getActiveById(taskId) ?: return
        when (operation) {
            is BulkOperation.EditField -> when (operation.fieldKey) {
                "summary" -> taskService.update(
                    id = task.id,
                    input = UpdateTaskInput(
                        summary = operation.newValue,
                        expectedVersion = task.version,
                    ),
                    actingPrincipalId = actingPrincipalId,
                    actingProfileId = actingProfileId,
                )
                else -> throw PendingPhaseImplementationException(
                    variant = "BulkOperation.EditField.${operation.fieldKey}", owningPhase = 11,
                )
            }
            is BulkOperation.AssignTo -> taskService.update(
                id = task.id,
                input = UpdateTaskInput(
                    assigneeProfileId = operation.profileId,
                    expectedVersion = task.version,
                ),
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
            )
            is BulkOperation.Delete -> taskService.softDelete(
                id = task.id, expectedVersion = task.version,
                actingPrincipalId = actingPrincipalId, actingProfileId = actingProfileId,
            )
            is BulkOperation.Transition,
            is BulkOperation.AddLabel,
            is BulkOperation.RemoveLabel -> throw PendingPhaseImplementationException(
                variant = "BulkOperation.${operation::class.simpleName}", owningPhase = 11,
            )
        }
    }
}
