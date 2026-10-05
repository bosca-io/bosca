package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.automation.AutomationOutcome
import bosca.workops.model.automation.AutomationRule
import bosca.workops.model.automation.AutomationScope
import bosca.workops.model.automation.Trigger
import bosca.workops.model.task.Task
import bosca.workops.repository.AutomationRuleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AutomationDispatcherTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val projectId = UUID.random()
    private val programId = UUID.random()
    private val portfolioId = UUID.random()
    private val profileId = UUID.random()
    private val principalId = UUID.random()
    private val task = Task(
        id = UUID.random(),
        key = "AUTO-1",
        projectId = projectId,
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Automation test task",
        reporterProfileId = profileId,
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    @Test
    fun `dispatcher routes every supported event with its context`() = runTest {
        val slaGoalId = UUID.random()
        val fromStatusId = UUID.random()
        val toStatusId = UUID.random()
        val environmentId = UUID.random()
        val rules = listOf(
            rule("created", Trigger.TaskCreated()),
            rule("updated", Trigger.TaskUpdated(fieldKeys = listOf("priorityId"))),
            rule(
                "transitioned",
                Trigger.TaskTransitioned(fromStatusIds = listOf(fromStatusId), toStatusIds = listOf(toStatusId)),
            ),
            rule("commented", Trigger.TaskCommented()),
            rule("deleted", Trigger.TaskDeleted()),
            rule("sla-breached", Trigger.SlaBreached(slaGoalIds = listOf(slaGoalId))),
            rule("sla-at-risk", Trigger.SlaAtRisk()),
            rule(
                "artifact-published",
                Trigger.ArtifactPublished(projectId = projectId, artifactTypes = listOf("android")),
            ),
            rule("pipeline-failed", Trigger.PipelineFailed(projectId = projectId, pipelineIds = listOf("build"))),
            rule(
                "pipeline-completed",
                Trigger.PipelineCompleted(projectId = projectId, pipelineIds = listOf("deploy")),
            ),
            rule("dependency-outdated", Trigger.DependencyOutdated(consumerProjectId = projectId)),
            rule("deployment-failed", Trigger.EnvironmentDeploymentFailed(environmentId)),
            rule("environment-unhealthy", Trigger.EnvironmentUnhealthy()),
            rule("promotion-ready", Trigger.EnvironmentPromotionReady(environmentId)),
        )
        val repository = repositoryWithGlobalRules(rules)
        val executor = mockk<AutomationExecutor>()
        val invocations = mutableListOf<Pair<AutomationRule, AutomationContext>>()
        coEvery { executor.run(any(), any()) } coAnswers {
            invocations += firstArg<AutomationRule>() to secondArg<AutomationContext>()
            AutomationOutcome.OK
        }
        val dispatcher = AutomationDispatcherImpl(repository, executor, json)

        dispatcher.fireTaskCreated(task, projectId, programId, portfolioId)
        dispatcher.fireTaskUpdated(task, projectId, programId, portfolioId, setOf("priorityId"))
        dispatcher.fireTaskTransitioned(task, projectId, programId, portfolioId, fromStatusId, toStatusId)
        dispatcher.fireTaskCommented(task, projectId, programId, portfolioId)
        dispatcher.fireTaskDeleted(task, projectId, programId, portfolioId)
        dispatcher.fireSlaBreached(task, projectId, programId, portfolioId, slaGoalId)
        dispatcher.fireSlaAtRisk(task, projectId, programId, portfolioId, slaGoalId)
        dispatcher.fireArtifactPublished(projectId, programId, portfolioId, "android", "com.example:app:1.0")
        dispatcher.firePipelineFailed(projectId, programId, portfolioId, "build")
        dispatcher.firePipelineCompleted(projectId, programId, portfolioId, "deploy")
        dispatcher.fireDependencyOutdated(projectId, programId, portfolioId)
        dispatcher.fireEnvironmentDeploymentFailed(projectId, programId, portfolioId, environmentId)
        dispatcher.fireEnvironmentUnhealthy(projectId, programId, portfolioId, environmentId)
        dispatcher.fireEnvironmentPromotionReady(projectId, programId, portfolioId, environmentId)

        val contexts = invocations.associate { it.first.name to it.second }
        assertEquals(rules.map { it.name }.toSet(), contexts.keys)
        assertEquals(task, contexts.getValue("created").triggeringTask)
        assertEquals(setOf("priorityId"), contexts.getValue("updated").changedKeys)
        assertEquals(
            mapOf("artifactType" to "android", "coordinates" to "com.example:app:1.0"),
            contexts.getValue("artifact-published").eventMetadata,
        )
        assertEquals(mapOf("pipelineId" to "build"), contexts.getValue("pipeline-failed").eventMetadata)
        assertEquals(mapOf("pipelineId" to "deploy"), contexts.getValue("pipeline-completed").eventMetadata)
        assertEquals(
            mapOf("environmentId" to environmentId.toString()),
            contexts.getValue("deployment-failed").eventMetadata,
        )
        assertEquals(projectId, contexts.getValue("dependency-outdated").projectId)

        coVerify(exactly = 14) { repository.listEnabled(AutomationScope.GLOBAL.name, null) }
        coVerify(exactly = 14) { repository.listEnabled(AutomationScope.PORTFOLIO.name, portfolioId) }
        coVerify(exactly = 14) { repository.listEnabled(AutomationScope.PROGRAM.name, programId) }
        coVerify(exactly = 14) { repository.listEnabled(AutomationScope.PROJECT.name, projectId) }
    }

    @Test
    fun `dispatcher ignores corrupt wrong and nonmatching triggers`() = runTest {
        val otherId = UUID.random()
        val rules = listOf(
            corruptRule(),
            rule("wrong-kind", Trigger.TaskCreated()),
            rule("updated-field", Trigger.TaskUpdated(fieldKeys = listOf("statusId"))),
            rule("transition-from", Trigger.TaskTransitioned(fromStatusIds = listOf(otherId))),
            rule("transition-to", Trigger.TaskTransitioned(toStatusIds = listOf(otherId))),
            rule("sla-breached", Trigger.SlaBreached(listOf(otherId))),
            rule("sla-at-risk", Trigger.SlaAtRisk(listOf(otherId))),
            rule("artifact-project", Trigger.ArtifactPublished(projectId = otherId)),
            rule("artifact-type", Trigger.ArtifactPublished(artifactTypes = listOf("ios"))),
            rule("pipeline-failed-project", Trigger.PipelineFailed(projectId = otherId)),
            rule("pipeline-failed-id", Trigger.PipelineFailed(pipelineIds = listOf("other"))),
            rule("pipeline-completed-project", Trigger.PipelineCompleted(projectId = otherId)),
            rule("pipeline-completed-id", Trigger.PipelineCompleted(pipelineIds = listOf("other"))),
            rule("dependency", Trigger.DependencyOutdated(otherId)),
            rule("deployment", Trigger.EnvironmentDeploymentFailed(otherId)),
            rule("unhealthy", Trigger.EnvironmentUnhealthy(otherId)),
            rule("promotion", Trigger.EnvironmentPromotionReady(otherId)),
        )
        val repository = repositoryWithGlobalRules(rules)
        val executor = mockk<AutomationExecutor>(relaxed = true)
        val dispatcher = AutomationDispatcherImpl(repository, executor, json)

        dispatcher.fireTaskUpdated(task, projectId, null, null, setOf("priorityId"))
        dispatcher.fireTaskTransitioned(task, projectId, null, null, null, task.statusId)
        dispatcher.fireTaskTransitioned(task, projectId, null, null, UUID.random(), task.statusId)
        dispatcher.fireSlaBreached(task, projectId, null, null, UUID.random())
        dispatcher.fireSlaAtRisk(task, projectId, null, null, UUID.random())
        dispatcher.fireArtifactPublished(projectId, null, null, "android", "coordinates")
        dispatcher.firePipelineFailed(projectId, null, null, "build")
        dispatcher.firePipelineCompleted(projectId, null, null, "deploy")
        dispatcher.fireDependencyOutdated(projectId, null, null)
        dispatcher.fireEnvironmentDeploymentFailed(projectId, null, null, UUID.random())
        dispatcher.fireEnvironmentUnhealthy(projectId, null, null, UUID.random())
        dispatcher.fireEnvironmentPromotionReady(projectId, null, null, UUID.random())

        coVerify(exactly = 0) { executor.run(any(), any()) }
        coVerify(exactly = 0) { repository.listEnabled(AutomationScope.PORTFOLIO.name, any()) }
        coVerify(exactly = 0) { repository.listEnabled(AutomationScope.PROGRAM.name, any()) }
    }

    @Test
    fun `dispatcher isolates rule failures but preserves cancellation`() = runTest {
        val failingRule = rule("failing", Trigger.TaskCreated())
        val followingRule = rule("following", Trigger.TaskCreated())
        val repository = repositoryWithGlobalRules(listOf(failingRule, followingRule))
        val executor = mockk<AutomationExecutor>()
        coEvery { executor.run(failingRule, any()) } throws IllegalStateException("failed")
        coEvery { executor.run(followingRule, any()) } returns AutomationOutcome.OK
        val dispatcher = AutomationDispatcherImpl(repository, executor, json)

        dispatcher.fireTaskCreated(task, projectId, null, null)
        coVerify(exactly = 1) { executor.run(followingRule, any()) }

        coEvery { executor.run(failingRule, any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> {
            dispatcher.fireTaskCreated(task, projectId, null, null)
        }
    }

    @Test
    fun `every route isolates ordinary failures and preserves cancellation`() = runTest {
        val slaGoalId = UUID.random()
        val fromStatusId = UUID.random()
        val toStatusId = UUID.random()
        val environmentId = UUID.random()
        val rules = listOf(
            rule("created", Trigger.TaskCreated()),
            rule("updated", Trigger.TaskUpdated()),
            rule("transitioned", Trigger.TaskTransitioned()),
            rule("commented", Trigger.TaskCommented()),
            rule("deleted", Trigger.TaskDeleted()),
            rule("sla-breached", Trigger.SlaBreached()),
            rule("sla-at-risk", Trigger.SlaAtRisk()),
            rule("artifact-published", Trigger.ArtifactPublished()),
            rule("pipeline-failed", Trigger.PipelineFailed()),
            rule("pipeline-completed", Trigger.PipelineCompleted()),
            rule("dependency-outdated", Trigger.DependencyOutdated()),
            rule("deployment-failed", Trigger.EnvironmentDeploymentFailed()),
            rule("environment-unhealthy", Trigger.EnvironmentUnhealthy()),
            rule("promotion-ready", Trigger.EnvironmentPromotionReady()),
        )
        val repository = repositoryWithGlobalRules(rules)
        val executor = mockk<AutomationExecutor>()
        val dispatcher = AutomationDispatcherImpl(repository, executor, json)
        val routes = listOf<suspend (AutomationDispatcher) -> Unit>(
            { it.fireTaskCreated(task, projectId, null, null) },
            { it.fireTaskUpdated(task, projectId, null, null, setOf("priorityId")) },
            { it.fireTaskTransitioned(task, projectId, null, null, fromStatusId, toStatusId) },
            { it.fireTaskCommented(task, projectId, null, null) },
            { it.fireTaskDeleted(task, projectId, null, null) },
            { it.fireSlaBreached(task, projectId, null, null, slaGoalId) },
            { it.fireSlaAtRisk(task, projectId, null, null, slaGoalId) },
            { it.fireArtifactPublished(projectId, null, null, "android", "coordinates") },
            { it.firePipelineFailed(projectId, null, null, "build") },
            { it.firePipelineCompleted(projectId, null, null, "deploy") },
            { it.fireDependencyOutdated(projectId, null, null) },
            { it.fireEnvironmentDeploymentFailed(projectId, null, null, environmentId) },
            { it.fireEnvironmentUnhealthy(projectId, null, null, environmentId) },
            { it.fireEnvironmentPromotionReady(projectId, null, null, environmentId) },
        )

        coEvery { executor.run(any(), any()) } throws IllegalStateException("failed")
        routes.forEach { route -> route(dispatcher) }
        coVerify(exactly = routes.size) { executor.run(any(), any()) }

        coEvery { executor.run(any(), any()) } throws CancellationException("cancelled")
        routes.forEach { route ->
            assertFailsWith<CancellationException> { route(dispatcher) }
        }
    }

    private fun repositoryWithGlobalRules(rules: List<AutomationRule>): AutomationRuleRepository {
        val repository = mockk<AutomationRuleRepository>()
        coEvery { repository.listEnabled(any(), any()) } returns emptyList()
        coEvery { repository.listEnabled(AutomationScope.GLOBAL.name, null) } returns rules
        return repository
    }

    private fun rule(name: String, trigger: Trigger): AutomationRule = AutomationRule(
        id = UUID.random(),
        scope = AutomationScope.GLOBAL,
        name = name,
        trigger = json.encodeToJsonElement(Trigger.serializer(), trigger),
        conditions = JsonArray(emptyList()),
        actions = JsonArray(emptyList()),
        runAsProfileId = profileId,
    )

    private fun corruptRule(): AutomationRule = AutomationRule(
        id = UUID.random(),
        scope = AutomationScope.GLOBAL,
        name = "corrupt",
        trigger = JsonObject(mapOf("type" to JsonPrimitive("UnknownTrigger"))),
        conditions = JsonArray(emptyList()),
        actions = JsonArray(emptyList()),
        runAsProfileId = profileId,
    )
}
