package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.capacity.CapacityReportEntry
import bosca.workops.model.okr.ConfidenceLevel
import bosca.workops.model.okr.KeyResult
import bosca.workops.model.okr.KeyResultMetric
import bosca.workops.model.okr.Objective
import bosca.workops.model.roadmap.RoadmapEntry
import bosca.workops.model.roadmap.RoadmapScenario
import kotlinx.serialization.json.JsonElement

// ----- Roadmap ----------------------------------------------------

interface RoadmapService : Service {
    suspend fun compute(programId: UUID, scenarioId: UUID? = null): List<RoadmapEntry>
    suspend fun listScenarios(programId: UUID): List<RoadmapScenario>
    suspend fun getScenario(id: UUID): RoadmapScenario?
    suspend fun createScenario(
        programId: UUID, name: String, description: String?,
        overrides: JsonElement, createdByProfileId: UUID,
    ): RoadmapScenario
    suspend fun deleteScenario(id: UUID)
    /**
     * Materializes the scenario's overrides through `TaskService.update`
     * one task at a time so audit attributes the change to the
     * committer. Returns the count of tasks updated.
     */
    suspend fun commitScenario(scenarioId: UUID, actingPrincipalId: UUID, actingProfileId: UUID?): Int
}

// ----- Dependency graph -------------------------------------------

data class DependencyNode(
    @kotlinx.serialization.Contextual val taskId: UUID,
    val key: String,
    val summary: String,
)

data class DependencyEdge(
    @kotlinx.serialization.Contextual val sourceTaskId: UUID,
    @kotlinx.serialization.Contextual val targetTaskId: UUID,
    @kotlinx.serialization.Contextual val linkTypeId: UUID,
    val category: String,
)

data class DependencyGraph(
    val nodes: List<DependencyNode>,
    val edges: List<DependencyEdge>,
    val cycles: List<List<UUID>>,
)

interface TaskDependencyService : Service {
    suspend fun graph(rootTaskId: UUID, depth: Int): DependencyGraph
}

// ----- OKRs --------------------------------------------------------

interface ObjectiveService : Service {
    suspend fun getById(id: UUID): Objective?
    suspend fun listForProgram(programId: UUID): List<Objective>
    suspend fun listForProject(projectId: UUID): List<Objective>
    suspend fun listForPortfolio(portfolioId: UUID): List<Objective>
    suspend fun create(input: Objective): Objective
}

interface KeyResultService : Service {
    suspend fun list(objectiveId: UUID): List<KeyResult>
    suspend fun create(
        objectiveId: UUID, title: String, description: String?,
        metric: KeyResultMetric, confidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    ): KeyResult
    suspend fun recompute(keyResultId: UUID): KeyResult?
}

// ----- Capacity ----------------------------------------------------

interface CapacityService : Service {
    suspend fun listForSprint(sprintId: UUID): List<bosca.workops.model.capacity.Capacity>
    suspend fun setCommitment(sprintId: UUID, profileId: UUID, committedSeconds: Long, notes: String?)
    suspend fun deleteCommitment(sprintId: UUID, profileId: UUID)
    suspend fun report(sprintId: UUID): List<CapacityReportEntry>
}
