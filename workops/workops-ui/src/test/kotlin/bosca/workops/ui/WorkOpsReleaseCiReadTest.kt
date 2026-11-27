package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.workops.ui.graphql.WorkOpsReleaseExecutionAccess
import bosca.workops.ui.graphql.WorkOpsReleaseNativeRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsReleaseCiReadTest {
    @Test fun `missing or incomplete access never grants repository actions and rollback waits for known runs`() = runTest {
        val read = WorkOpsReleaseCiReadModel("release")
        read.retry(context())
        assertFalse(read.canEdit("unavailable"))
        assertFalse(read.canExecute("unavailable"))
        read.accessLoaded = false
        assertFalse(read.canEdit("repo"))
        assertFalse(read.canExecute("repo"))
        assertEquals("Action permissions are unavailable.", read.startReason)
        assertEquals("Action permissions are unavailable.", read.rollbackAttemptReason)
        assertEquals("Action permissions are unavailable.", read.rollbackEnvironmentReason("testing"))
        read.accessLoaded = true
        read.access = null
        assertFalse(read.canManage)
        assertFalse(read.canEdit("repo"))
        assertFalse(read.canExecute("repo"))
        read.access = WorkOpsReleaseExecutionAccess.decodeData(access(manage = false)).workOps.crossProject.releaseActionAccess
        assertEquals("Manage permission on the release program is required.", read.rollbackAttemptReason)
        read.access = WorkOpsReleaseExecutionAccess.decodeData(access()).workOps.crossProject.releaseActionAccess
        read.componentsLoaded = false
        assertEquals("Bundled versions are unavailable.", read.startReason)
        read.componentsLoaded = true
        read.plansLoaded = false
        assertEquals("Release plans are unavailable.", read.startReason)
        assertEquals("Release plans are unavailable.", read.promotionReason("production"))
        read.plansLoaded = true
        read.summariesLoaded = false
        assertEquals("Release run status is unavailable.", read.rollbackAttemptReason)
        assertEquals("Release run details are unavailable.", read.rollbackEnvironmentReason("testing"))
        read.summariesLoaded = true
        val summary = read.summaries.single()
        read.summaries = emptyList()
        assertEquals("This release has no native runs yet.", read.rollbackAttemptReason)
        read.summaries = listOf(summary.copy(status = "QUEUED"))
        assertEquals("Cancel live runs before rolling back the release attempt.", read.rollbackAttemptReason)
        read.release = read.release?.copy(released = "2026-11-17")
        assertEquals("This release is already marked released.", read.rollbackAttemptReason)
        read.release = null
        assertEquals("This release is unavailable.", read.rollbackAttemptReason)
        assertEquals("This release is unavailable.", read.startReason)
    }
    private fun json(s: String) = Json.parseToJsonElement(s)
    private fun cross(field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject("crossProject") { put(field, value) } } }
    private val release = json("""{"id":"release","programId":"program","name":"Release","description":null,"releaseDate":null,"releasedAt":null,"version":7}""")
    private val component = json("""{"releaseId":"release","projectId":"project","versionId":"version","deploymentOrder":null,"deploymentStatus":"PENDING","deployedAt":null,"rollbackVersionId":null}""")
    private fun plans() = cross("releasePlans", json("""[
        {"pipelineId":"pipeline","pipelineName":"Release","repositoryId":"repo","repositorySlug":"source","triggerType":"RELEASE","environment":null,"promotesFrom":null,"inputs":[],"jobs":[{"key":"build","environment":"testing","approvalRequired":false,"needs":[],"requirements":[],"depth":0,"steps":[]}]},
        {"pipelineId":"pipeline","pipelineName":"Release","repositoryId":"repo","repositorySlug":"source","triggerType":"PROMOTION","environment":"production","promotesFrom":"testing","inputs":[],"jobs":[]}
    ]"""))
    private fun access(manage: Boolean = true, edit: Boolean = true, execute: Boolean = true, environment: Boolean = true, repositories: Boolean = true): JsonElement {
        val rows = if (repositories) """[{"repositoryId":"repo","canEdit":$edit,"canExecute":$execute}]""" else "[]"
        return cross("releaseActionAccess", json("""{"canManage":$manage,"repositories":$rows,"environments":[{"key":"testing","canExecute":$environment},{"key":"production","canExecute":$environment}]}"""))
    }
    private fun native(id: String = "run", pipeline: String = "pipeline", status: String = "SUCCESS", trigger: String = "RELEASE", environment: String? = null) = buildJsonObject {
        putJsonObject("git") { putJsonObject("pipelineRun") {
            put("id", id); put("pipelineId", pipeline); put("repositoryId", "repo"); put("ref", "refs/heads/main"); put("triggerType", trigger); put("status", status); put("number", 1)
            put("parameters", buildJsonObject { environment?.let { put("promotion.environment", it) } }); put("created", "2027-01-01T00:00:00Z")
            for (key in listOf("started", "finished", "durationSeconds")) put(key, JsonNull)
            put("jobs", JsonArray(emptyList()))
        } }
    }
    private fun summaries(extra: Boolean = false) = cross("releaseRuns", JsonArray(buildList {
        add(json("""{"runId":"run","status":"SUCCESS","startedAt":"2027-01-01T00:00:00Z","finishedAt":null,"durationMs":null}"""))
        if (extra) add(json("""{"runId":"unavailable","status":"RUNNING","startedAt":"2027-01-01T00:00:00Z","finishedAt":null,"durationMs":null}"""))
    }))
    private fun context(overrides: suspend (String, JsonObject) -> JsonElement? = { _, _ -> null }) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
            val op = requireNotNull(operationName)
            return overrides(op, variables ?: JsonObject(emptyMap())) ?: when (op) {
                "WorkOpsReleaseDetail" -> cross("release", release)
                "WorkOpsReleaseComponents" -> cross("versionsForRelease", JsonArray(listOf(component)))
                "WorkOpsVersionProjects" -> json("""{"workOps":{"projects":{"all":[]}}}""")
                "WorkOpsReleasePlans" -> plans()
                "WorkOpsReleaseExecutionAccess" -> access()
                "WorkOpsReleaseReadiness" -> cross("releaseReadiness", JsonArray(emptyList()))
                "WorkOpsReleaseRunSummaries" -> summaries()
                "WorkOpsReleaseNativeRun" -> native()
                else -> error(op)
            }
        }
    })

    @Test fun `empty bundles expose capabilities without treating a missing launch plan as an error`() = runTest {
        val calls = mutableListOf<String>(); val model = WorkOpsReleaseCiReadModel("release")
        model.retry(context { operation, _ ->
            calls += operation
            when (operation) {
                "WorkOpsReleaseComponents" -> cross("versionsForRelease", JsonArray(emptyList()))
                "WorkOpsReleaseRunSummaries" -> cross("releaseRuns", JsonArray(emptyList()))
                else -> null
            }
        })
        assertFalse(model.feedback.failed); assertTrue(model.canManage); assertTrue(model.plansLoaded); assertTrue(model.plans.isEmpty())
        assertFalse("WorkOpsReleasePlans" in calls); assertFalse("WorkOpsVersionProjects" in calls)
        assertTrue(model.startReason.orEmpty().contains("Add at least one")); assertFalse(model.needsPolling)
    }

    @Test fun `plan and readiness failures remain unavailable without hiding independent permissions or run history`() = runTest {
        val model = WorkOpsReleaseCiReadModel("release")
        model.retry(context { operation, _ -> if (operation == "WorkOpsReleasePlans") error("No attached repositories") else null })
        assertTrue(model.plansFeedback.failed); assertFalse(model.plansLoaded); assertTrue(model.canManage); assertTrue(model.summariesLoaded)
        assertEquals("Release plans are unavailable.", model.startReason)
        model.retry(context { operation, _ -> if (operation == "WorkOpsReleaseReadiness") error("Readiness offline") else null })
        assertTrue(model.plansLoaded); assertFalse(model.readinessLoaded); assertEquals("Release readiness is unavailable.", model.startReason)
        model.retry(context { operation, _ -> if (operation == "WorkOpsReleaseExecutionAccess") cross("releaseActionAccess", JsonNull) else null })
        assertFalse(model.accessLoaded); assertFalse(model.canManage); assertEquals("Action permissions are unavailable.", model.startReason)
    }

    @Test fun `a confirmed absence of repositories is setup guidance and attaching one restores plan reads`() = runTest {
        val model = WorkOpsReleaseCiReadModel("release")
        model.retry(context())
        assertTrue(model.plans.isNotEmpty())
        val calls = mutableListOf<String>()
        model.retry(context { operation, _ ->
            calls += operation
            when (operation) {
                "WorkOpsReleaseExecutionAccess" -> access(repositories = false)
                "WorkOpsReleasePlans" -> error("A release without repositories cannot have plans")
                "WorkOpsReleaseReadiness" -> cross("releaseReadiness", JsonArray(listOf(JsonPrimitive("Project has no repositories attached"))))
                else -> null
            }
        })
        assertTrue(model.repositoriesMissing)
        assertTrue(model.plansLoaded); assertTrue(model.plans.isEmpty()); assertFalse(model.plansFeedback.failed)
        assertFalse("WorkOpsReleasePlans" in calls)
        assertEquals("Attach a Git repository to a bundled project before starting the release.", model.startReason)
        assertEquals(listOf("Project has no repositories attached"), model.readiness)
        assertTrue(model.summariesLoaded); assertTrue(model.runs.isNotEmpty())
        model.retry(context { operation, _ -> calls += operation; null })
        assertFalse(model.repositoriesMissing); assertTrue("WorkOpsReleasePlans" in calls)
        assertTrue(model.plans.isNotEmpty()); assertNull(model.startReason)
    }

    @Test fun `unavailable access or bundles cannot turn a plan failure into a setup state`() = runTest {
        for (missing in listOf("WorkOpsReleaseExecutionAccess", "WorkOpsReleaseComponents")) {
            val model = WorkOpsReleaseCiReadModel("release")
            val calls = mutableListOf<String>()
            model.retry(context { operation, _ ->
                calls += operation
                when (operation) {
                    missing -> error("Read unavailable")
                    "WorkOpsReleaseExecutionAccess" -> access(repositories = false)
                    "WorkOpsReleasePlans" -> error("Plan transport unavailable")
                    else -> null
                }
            })
            assertTrue("WorkOpsReleasePlans" in calls)
            assertEquals(missing == "WorkOpsReleaseComponents", model.repositoriesMissing)
            assertFalse(model.plansLoaded); assertEquals("Plan transport unavailable", model.plansFeedback.message)
        }
        val model = WorkOpsReleaseCiReadModel("release")
        model.retry(context { operation, _ -> if (operation == "WorkOpsReleaseExecutionAccess") cross("releaseActionAccess", JsonNull) else null })
        assertFalse(model.repositoriesMissing); assertTrue(model.plansLoaded)
        model.accessLoaded = true
        assertFalse(model.repositoriesMissing)
    }

    @Test fun `program management repository edit repository execute and environment execute are independent`() = runTest {
        val model = WorkOpsReleaseCiReadModel("release"); model.retry(context())
        fun permissions(manage: Boolean = true, edit: Boolean = true, execute: Boolean = true, environment: Boolean = true) {
            model.access = WorkOpsReleaseExecutionAccess.decodeData(access(manage, edit, execute, environment)).workOps.crossProject.releaseActionAccess
        }
        assertNull(model.startReason); assertNull(model.promotionReason("production"))
        permissions(manage = false, edit = false)
        assertFalse(model.canManage); assertFalse(model.canEdit("repo")); assertTrue(model.canExecute("repo")); assertTrue(model.startReason.orEmpty().contains("Manage permission"))
        permissions(edit = false)
        assertTrue(model.canExecute("repo")); assertTrue(model.startReason.orEmpty().contains("Edit permission")); assertTrue(model.promotionReason("production").orEmpty().contains("Edit permission"))
        permissions(execute = false)
        assertTrue(model.canEdit("repo")); assertTrue(model.startReason.orEmpty().contains("Execute permission"))
        permissions(environment = false)
        assertNull(model.startReason); assertEquals("Execute permission on production is required.", model.promotionReason("production"))
        assertFalse(model.canExecuteEnvironment("production")); assertTrue(model.canExecuteEnvironment(null))
    }

    @Test fun `promotion sources require a successful matching pipeline and exact environment`() = runTest {
        val model = WorkOpsReleaseCiReadModel("release"); model.retry(context())
        val plan = model.promotionPlans.single()
        fun run(pipeline: String = "pipeline", status: String = "SUCCESS", trigger: String = "RELEASE", environment: String? = null) {
            model.runs = listOf(requireNotNull(WorkOpsReleaseNativeRun.decodeData(native(pipeline = pipeline, status = status, trigger = trigger, environment = environment)).git.pipelineRun))
        }
        run(pipeline = "other"); assertFalse(model.sourceReached(plan))
        run(status = "RUNNING"); assertFalse(model.sourceReached(plan))
        run(trigger = "PROMOTION", environment = "other"); assertFalse(model.sourceReached(plan))
        assertEquals("Promote through testing first.", model.promotionReason("production"))
        run(trigger = "PROMOTION", environment = "testing"); assertTrue(model.sourceReached(plan)); assertNull(model.promotionReason("production"))
        run(); assertTrue(model.sourceReached(plan)); assertTrue(model.environmentReached("testing"))
    }

    @Test fun `an unavailable run preserves explicit selection and does not erase another confirmed source success`() = runTest {
        val model = WorkOpsReleaseCiReadModel("release", selectedRunId = "unavailable")
        model.retry(context { operation, variables -> when {
            operation == "WorkOpsReleaseRunSummaries" -> summaries(extra = true)
            operation == "WorkOpsReleaseNativeRun" && variables.getValue("id").jsonPrimitive.content == "unavailable" -> error("Git view denied")
            else -> null
        } })
        assertEquals("unavailable", model.chosenRunId); assertNull(model.selectedRun); assertEquals(setOf("unavailable"), model.runErrors.keys)
        assertEquals(1, model.runs.size); assertTrue(model.needsPolling)
        assertNull(model.promotionReason("production")); assertNull(model.rollbackEnvironmentReason("testing"))
        model.runs = emptyList(); assertEquals("Source run details are unavailable.", model.promotionReason("production"))
        assertEquals("Release run details are unavailable.", model.rollbackEnvironmentReason("testing"))
    }

    @Test fun `attempt rollback respects release completion and active runs without inventing repository edit requirements`() = runTest {
        val model = WorkOpsReleaseCiReadModel("release"); model.retry(context())
        model.access = WorkOpsReleaseExecutionAccess.decodeData(access(edit = false, execute = false)).workOps.crossProject.releaseActionAccess
        assertNull(model.rollbackAttemptReason)
        model.release = model.release?.copy(released = "2027-01-01T00:00:00Z"); assertTrue(model.rollbackAttemptReason.orEmpty().contains("already marked released"))
        model.release = model.release?.copy(released = ""); model.summariesLoaded = false; assertTrue(model.rollbackAttemptReason.orEmpty().contains("unavailable"))
        model.summariesLoaded = true; model.summaries = model.summaries.map { it.copy(status = "RUNNING") }; assertTrue(model.rollbackAttemptReason.orEmpty().contains("Cancel live runs"))
    }

    @Test fun `cancellation inside a run lookup propagates through independent read feedback`() = runTest {
        val model = WorkOpsReleaseCiReadModel("release")
        assertFailsWith<CancellationException> { model.retry(context { operation, _ -> if (operation == "WorkOpsReleaseNativeRun") throw CancellationException("Navigation") else null }) }
    }
}
