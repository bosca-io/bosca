package bosca.workops.service

import bosca.db.transaction
import bosca.profile.profile.service.ProfileService
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.LinkCycleException
import bosca.workops.model.LinkGraphTooDeepException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.links.LinkCategory
import bosca.workops.model.links.TaskLink
import bosca.workops.model.links.CreateTaskLinkTypeInput
import bosca.workops.model.links.TaskLinkInput
import bosca.workops.model.links.TaskLinkType
import bosca.workops.model.links.UpdateTaskLinkTypeInput
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.NotificationEvent
import bosca.workops.model.notification.dispatch
import bosca.workops.repository.TaskLinkRepository

@ServiceImplementation
class TaskLinkServiceImpl(
    private val linkRepository: TaskLinkRepository,
    private val taskService: TaskService,
    private val securityService: SecurityService,
    private val profileService: ProfileService,
) : TaskLinkService {

    override suspend fun listLinkTypes(): List<TaskLinkType> = linkRepository.listLinkTypes()

    override suspend fun getLinkType(id: UUID): TaskLinkType? = linkRepository.getLinkTypeById(id)

    override suspend fun getById(id: UUID): TaskLink? = linkRepository.getById(id)

    override suspend fun listForTask(taskId: UUID): List<TaskLink> = linkRepository.listByTask(taskId)

    override suspend fun link(input: TaskLinkInput, actingPrincipalId: UUID): TaskLink = transaction {
        if (input.sourceTaskId == input.targetTaskId) {
            throw WorkOpsValidationException("targetTaskId", "self-links are not permitted")
        }
        val linkType = linkRepository.getLinkTypeById(input.linkTypeId)
            ?: throw WorkOpsNotFoundException("TaskLinkType", input.linkTypeId.toString())
        // The service confirms both endpoints exist before crashing on
        // the cycle check — the FK would catch it eventually but the
        // typed `NotFound` reads better at the API boundary.
        val sourceTask = taskService.getById(input.sourceTaskId)
            ?: throw WorkOpsNotFoundException("Task", input.sourceTaskId.toString())
        val targetTask = taskService.getById(input.targetTaskId)
            ?: throw WorkOpsNotFoundException("Task", input.targetTaskId.toString())

        if (linkType.category == LinkCategory.BLOCKS) {
            // Cycle check: if `target` already (transitively) blocks
            // `source`, inserting `source -> target` closes a cycle.
            val reachable = bfsReachable(start = input.targetTaskId, category = LinkCategory.BLOCKS)
            if (input.sourceTaskId in reachable) {
                throw LinkCycleException(input.sourceTaskId, input.targetTaskId)
            }
        }

        val inserted = linkRepository.add(
            linkTypeId = input.linkTypeId,
            sourceTaskId = input.sourceTaskId,
            targetTaskId = input.targetTaskId,
            createdByPrincipalId = actingPrincipalId,
        )
        // Idempotent re-insertion: the unique constraint suppressed
        // the row, so fetch the original and return it.
        if (inserted != null) {
            val actorProfileId = securityService.getPrincipalById(actingPrincipalId)?.let { principal ->
                principal.primaryProfileId ?: profileService.getPrimaryProfile(principal)?.id
            }
            listOf(sourceTask, targetTask).forEach { task ->
                NotificationDeliveryRequested(
                    NotificationDelivery(
                        event = NotificationEvent.TASK_LINKED,
                        taskId = task.id,
                        projectId = task.projectId,
                        actorProfileId = actorProfileId,
                    ),
                ).dispatch()
            }
            inserted
        } else linkRepository.getByTriple(
            sourceTaskId = input.sourceTaskId,
            targetTaskId = input.targetTaskId,
            linkTypeId = input.linkTypeId,
        ) ?: error("link insert dropped by ON CONFLICT but no matching row found")
    }

    override suspend fun unlink(linkId: UUID) {
        linkRepository.deleteById(linkId)
    }

    override suspend fun createLinkType(input: CreateTaskLinkTypeInput): TaskLinkType =
        linkRepository.addLinkType(input.name, input.inwardLabel, input.outwardLabel, input.category)

    override suspend fun updateLinkType(id: UUID, input: UpdateTaskLinkTypeInput): TaskLinkType =
        linkRepository.updateLinkType(id, input.name, input.inwardLabel, input.outwardLabel, input.category, input.expectedVersion)
            ?: error("TaskLinkType $id not found or version mismatch")

    override suspend fun deleteLinkType(id: UUID) = linkRepository.deleteLinkTypeById(id)

    /**
     * BFS from [start] following links of [category]. Returns the
     * set of reachable task ids. Bounded at [MAX_GRAPH_NODES];
     * graphs whose visited set hits the bound raise
     * [LinkGraphTooDeepException] rather than continuing to search.
     */
    private suspend fun bfsReachable(start: UUID, category: LinkCategory): Set<UUID> {
        val visited = LinkedHashSet<UUID>()
        val queue = ArrayDeque<UUID>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            if (visited.size > MAX_GRAPH_NODES) {
                throw LinkGraphTooDeepException(start)
            }
            val neighbors = linkRepository.blockedTargets(current, category)
            queue.addAll(neighbors)
        }
        return visited
    }

    companion object {
        private const val MAX_GRAPH_NODES = 256
    }
}
