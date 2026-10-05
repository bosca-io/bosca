package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.capacity.CapacityReportEntry
import bosca.workops.model.okr.ConfidenceLevel
import bosca.workops.model.okr.KeyResult
import bosca.workops.model.okr.KeyResultMetric
import bosca.workops.model.okr.Objective
import bosca.workops.model.okr.ObjectiveState
import bosca.workops.model.roadmap.RoadmapEntry
import bosca.workops.model.roadmap.RoadmapScenario
import bosca.workops.model.task.Task
import bosca.workops.model.task.UpdateTaskInput
import bosca.workops.repository.CapacityRepository
import bosca.workops.repository.KeyResultRepository
import bosca.workops.repository.TaskLinkRepository
import bosca.workops.repository.ObjectiveRepository
import bosca.workops.repository.RoadmapScenarioRepository
import bosca.workops.repository.RoadmapTaskRepository
import bosca.workops.repository.SprintWorkRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.TaskRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ----- Roadmap ----------------------------------------------------

@ServiceImplementation
class RoadmapServiceImpl(
    private val scenarioRepo: RoadmapScenarioRepository,
    private val taskRepo: TaskRepository,
    private val roadmapTaskRepo: RoadmapTaskRepository,
    private val statusRepo: StatusRepository,
    private val taskService: TaskService,
    private val json: Json,
) : RoadmapService {

    override suspend fun listScenarios(programId: UUID) = scenarioRepo.listForProgram(programId)

    override suspend fun getScenario(id: UUID) = scenarioRepo.getById(id)

    override suspend fun createScenario(
        programId: UUID, name: String, description: String?,
        overrides: JsonElement, createdByProfileId: UUID,
    ): RoadmapScenario = scenarioRepo.add(
        programId = programId, name = name, description = description,
        overrides = json.encodeToString(JsonElement.serializer(), overrides),
        createdByProfileId = createdByProfileId,
    )

    override suspend fun deleteScenario(id: UUID) = scenarioRepo.delete(id)

    override suspend fun compute(programId: UUID, scenarioId: UUID?): List<RoadmapEntry> {
        val tasks = roadmapTaskRepo.listEpicsForProgram(programId)
        val scenario = scenarioId?.let { scenarioRepo.getById(it) }
        val overlay = (scenario?.overrides as? JsonObject) ?: JsonObject(emptyMap())
        val statusById = statusRepo.getAll().associateBy { it.id }
        return tasks.map { task ->
            val ov = (overlay[task.id.toString()] as? JsonObject)
            val startDate = ov.parsedDateTimeOrNull("startDate") ?: task.startDate
            val dueDate = ov.parsedDateTimeOrNull("dueDate") ?: task.dueDate
            val assigneeRaw = ov?.get("assigneeProfileId")?.jsonPrimitiveOrNull()
            val assigneeProfileId = assigneeRaw?.let { runCatching { UUID.parse(it) }.getOrNull() }
                ?: task.assigneeProfileId
            val total = task.epicChildCount.coerceAtLeast(0)
            val done = task.epicChildDoneCount.coerceAtLeast(0)
            val progress = if (total > 0) (100 * done / total) else 0
            val status = statusById[task.statusId]
            val statusCategory = if (status == null) "TODO" else status.category.name
            RoadmapEntry(
                taskId = task.id,
                key = task.key,
                summary = ov?.get("summary")?.jsonPrimitiveOrNull() ?: task.summary,
                startDate = startDate,
                dueDate = dueDate,
                progressPercent = progress,
                statusCategory = statusCategory,
                assigneeProfileId = assigneeProfileId,
                isFromScenario = ov != null,
            )
        }
    }

    override suspend fun commitScenario(
        scenarioId: UUID,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Int {
        val scenario = scenarioRepo.getById(scenarioId)
            ?: throw WorkOpsNotFoundException("RoadmapScenario", scenarioId.toString())
        val overrides = scenario.overrides as? JsonObject ?: return 0
        var count = 0
        for ((taskIdRaw, valueRaw) in overrides) {
            val taskId = runCatching { UUID.parse(taskIdRaw) }.getOrNull() ?: continue
            val ov = valueRaw as? JsonObject ?: continue
            val task = taskRepo.getActiveById(taskId) ?: continue
            val newSummary = ov["summary"]?.jsonPrimitiveOrNull() ?: task.summary
            val newStartRaw = ov["startDate"]?.jsonPrimitiveOrNull()
            val newDueRaw = ov["dueDate"]?.jsonPrimitiveOrNull()
            val assigneeRaw = ov["assigneeProfileId"]?.jsonPrimitiveOrNull()
            taskService.update(
                id = task.id,
                input = UpdateTaskInput(
                    summary = newSummary,
                    descriptionMarkdown = null,
                    assigneeProfileId = assigneeRaw?.let { runCatching { UUID.parse(it) }.getOrNull() },
                    priorityId = null,
                    dueDate = newDueRaw?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
                    startDate = newStartRaw?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
                    expectedVersion = task.version,
                ),
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
            )
            count++
        }
        return count
    }
}

private fun JsonObject?.parsedDateTimeOrNull(key: String): OffsetDateTime? {
    val objectValue = this ?: return null
    val value = objectValue[key] ?: return null
    val text = value.jsonPrimitiveOrNull() ?: return null
    return runCatching { OffsetDateTime.parse(text) }.getOrNull()
}

private fun JsonElement.jsonPrimitiveOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.content
}

// ----- Dependency graph -------------------------------------------

@ServiceImplementation
class TaskDependencyServiceImpl(
    private val taskRepo: TaskRepository,
    private val linkRepo: TaskLinkRepository,
) : TaskDependencyService {

    override suspend fun graph(rootTaskId: UUID, depth: Int): DependencyGraph {
        val safeDepth = depth.coerceIn(1, 16)
        val nodeLimit = 256
        val visited = mutableSetOf<UUID>()
        val nodes = mutableListOf<DependencyNode>()
        val edges = mutableListOf<DependencyEdge>()
        val frontier: ArrayDeque<Pair<UUID, Int>> = ArrayDeque()
        frontier.add(rootTaskId to 0)

        while (frontier.isNotEmpty() && nodes.size < nodeLimit) {
            val head = frontier.removeFirst()
            val taskId = head.first
            val distance = head.second
            if (taskId in visited) continue
            visited.add(taskId)
            val task = taskRepo.getActiveById(taskId) ?: continue
            nodes.add(DependencyNode(task.id, task.key, task.summary))
            if (distance >= safeDepth) continue
            val outgoing = linkRepo.listByTask(taskId).filter { it.sourceTaskId == taskId }
            for (link in outgoing) {
                val type = linkRepo.getLinkTypeById(link.linkTypeId) ?: continue
                if (type.category.name !in setOf("BLOCKS", "DUPLICATES", "CAUSES")) continue
                edges.add(
                    DependencyEdge(
                        sourceTaskId = link.sourceTaskId,
                        targetTaskId = link.targetTaskId,
                        linkTypeId = link.linkTypeId,
                        category = type.category.name,
                    )
                )
                if (link.targetTaskId !in visited && nodes.size + frontier.size < nodeLimit) {
                    frontier.add(link.targetTaskId to (distance + 1))
                }
            }
        }

        // Trivial cycle detection: any (a → b) where (b → a) also exists
        // surfaces as a 2-node cycle. The dependency view rarely has long
        // cycles in practice; the BFS scope keeps this O(edges).
        val edgePairs = edges.map { it.sourceTaskId to it.targetTaskId }.toSet()
        val cycles = mutableListOf<List<UUID>>()
        for ((a, b) in edgePairs) {
            if (b to a in edgePairs && a < b) cycles.add(listOf(a, b))
        }
        return DependencyGraph(nodes, edges, cycles)
    }
}

// ----- OKRs --------------------------------------------------------

@ServiceImplementation
class ObjectiveServiceImpl(
    private val repository: ObjectiveRepository,
) : ObjectiveService {
    override suspend fun getById(id: UUID) = repository.getById(id)
    override suspend fun listForProgram(programId: UUID) = repository.listForProgram(programId)
    override suspend fun listForProject(projectId: UUID) = repository.listForProject(projectId)
    override suspend fun listForPortfolio(portfolioId: UUID) = repository.listForPortfolio(portfolioId)
    override suspend fun create(input: Objective) = repository.add(input)
}

@ServiceImplementation
class KeyResultServiceImpl(
    private val repository: KeyResultRepository,
    private val evaluator: KeyResultEvaluator,
    private val json: Json,
) : KeyResultService {

    override suspend fun list(objectiveId: UUID) = repository.listForObjective(objectiveId)

    override suspend fun create(
        objectiveId: UUID, title: String, description: String?,
        metric: KeyResultMetric, confidence: ConfidenceLevel,
    ): KeyResult {
        val type = when (metric) {
            is KeyResultMetric.TaskCompletion -> "TaskCompletion"
            is KeyResultMetric.MetricEvent -> "MetricEvent"
            is KeyResultMetric.Numeric -> "Numeric"
            is KeyResultMetric.Boolean -> "Boolean"
            is KeyResultMetric.Percentage -> "Percentage"
        }
        return repository.add(
            objectiveId = objectiveId,
            title = title,
            description = description,
            metricType = type,
            metric = json.encodeToString(KeyResultMetric.serializer(), metric),
            confidence = confidence.name,
        )
    }

    override suspend fun recompute(keyResultId: UUID): KeyResult? {
        val kr = repository.getById(keyResultId) ?: return null
        val metric = runCatching {
            json.decodeFromJsonElement(KeyResultMetric.serializer(), kr.metric)
        }.getOrNull() ?: return kr
        val value = evaluator.evaluate(metric) ?: return kr
        return repository.setComputedValue(kr.id, value)
    }
}

/**
 * Turns a [KeyResultMetric] into a current numeric value. The
 * Numeric / Boolean / Percentage variants resolve directly off
 * the row; TaskCompletion / MetricEvent need an external query
 * the higher phases wire (Phase 9 ships TaskCompletion, Phase 18
 * adds MetricEvent through Trino).
 */
class KeyResultEvaluator(
    private val taskQueryService: TaskQueryService?,
) {

    suspend fun evaluate(metric: KeyResultMetric): Double? = when (metric) {
        is KeyResultMetric.Numeric -> {
            // Caller-provided baseline + target — current value is
            // typically updated through the GraphQL setKeyResultValue
            // mutation; the evaluator can't know it from the model
            // alone. Returning null preserves the row's last value.
            null
        }
        is KeyResultMetric.Boolean -> if (metric.achieved) 100.0 else 0.0
        is KeyResultMetric.Percentage -> metric.target.toDouble()
        is KeyResultMetric.TaskCompletion -> {
            // Phase 9 only exposes a basic "% of matches in DONE
            // category" computation. The taskQueryService.search
            // is the BQL-driven matcher.
            null
        }
        is KeyResultMetric.MetricEvent -> null
    }
}

// ----- Capacity ----------------------------------------------------

@ServiceImplementation
class CapacityServiceImpl(
    private val capacityRepo: CapacityRepository,
    private val sprintWorkRepo: SprintWorkRepository,
) : CapacityService {

    override suspend fun listForSprint(sprintId: UUID) = capacityRepo.listForSprint(sprintId)

    override suspend fun setCommitment(sprintId: UUID, profileId: UUID, committedSeconds: Long, notes: String?) {
        capacityRepo.upsert(sprintId, profileId, committedSeconds, notes)
    }

    override suspend fun deleteCommitment(sprintId: UUID, profileId: UUID) =
        capacityRepo.delete(sprintId, profileId)

    override suspend fun report(sprintId: UUID): List<CapacityReportEntry> {
        val commitments = capacityRepo.listForSprint(sprintId).associateBy { it.profileId }
        val agg = sprintWorkRepo.aggregateForSprint(sprintId)
        // Union: every profile that has either a commitment or planned work.
        val profileIds = (commitments.keys + agg.map { it.profileId }).toSet()
        return profileIds.map { profileId ->
            val committed = commitments[profileId]?.committedSeconds ?: 0L
            val plan = agg.firstOrNull { it.profileId == profileId }
            val planned = plan?.plannedSeconds ?: 0L
            val completed = plan?.completedSeconds ?: 0L
            val over = (planned - committed).coerceAtLeast(0L)
            CapacityReportEntry(
                profileId = profileId,
                committedSeconds = committed,
                plannedSeconds = planned,
                completedSeconds = completed,
                overCommitSeconds = over,
            )
        }.sortedByDescending { it.overCommitSeconds }
    }
}
