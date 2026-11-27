package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.workops.ui.graphql.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsReleaseCiActionsTest {
    @Test fun `unavailable release actions cannot open launch or rollback dialogs`() = runTest {
        val page = WorkOpsReleaseCiModel("release")
        page.openStart()
        assertTrue(page.feedback.failed)
        assertNull(page.launching)
        page.openPromotion("production")
        assertTrue(page.feedback.failed)
        assertNull(page.launching)
        page.openAttemptRollback()
        assertTrue(page.feedback.failed)
        assertNull(page.confirming)
        page.openEnvironmentRollback("production")
        assertTrue(page.feedback.failed)
        assertNull(page.confirming)
    }
    @Test fun `run selection and controls reject missing or unrelated native identities`() = runTest {
        val page = model(status = "RUNNING", jobStatus = "RUNNING")
        assertFailsWith<IllegalArgumentException> { page.selectRun("other") }
        page.selectRun("run")
        assertEquals("run", page.read.selectedRunId)
        page.openControl("cancelJob", "missing", "job")
        assertEquals("Refresh the run details first.", page.feedback.message)
        page.openControl("cancelJob", "run", "missing")
        assertEquals("The job does not belong to this run.", page.feedback.message)
        val run = page.read.runs.single()
        assertEquals("The job is no longer active.", page.controlReason("cancelJob", run))
        assertEquals("Choose an available run action.", page.controlReason("unknown", run))
        page.control(true, context { _, _ -> throw AssertionError("Unselected control cannot fetch or write") })
        assertEquals("Choose a run action first.", page.feedback.message)
        page.confirming = WorkOpsReleaseCiAction("cancelRun", "run")
        page.control(true, context { _, _ -> json("""{"git":{"pipelineRun":null}}""") })
        assertEquals("Run details are unavailable.", page.feedback.message)
        page.confirming = WorkOpsReleaseCiAction("cancelJob", "run", "missing")
        page.control(true, context { _, _ -> native("RUNNING", "RUNNING") })
        assertEquals("The job does not belong to this run.", page.feedback.message)
    }

    @Test fun `patch dialog rejects malformed missing and unavailable project choices without submitting`() = runTest {
        val page = model()
        val ctx = context { _, _ -> throw AssertionError("Invalid patch cannot write") }
        page.patch("{}", ctx)
        assertEquals("Open the patch release dialog first.", page.feedback.message)
        page.openPatch()
        for (raw in listOf("[]", "{}", """{"projects":false}""", """{"projects":[{}]}""", """{"projects":[null]}""",
            """{"projects":[]}""", """{"projects":["unavailable"]}""")) {
            page.patch(raw, ctx)
            assertTrue(page.feedback.failed, raw)
            assertTrue(page.patching)
        }
        page.cancelPatch()
        assertFalse(page.patching)
        page.rollbackAttempt(true, ctx)
        assertEquals("Review the attempt rollback first.", page.feedback.message)
        page.rollbackEnvironment(true, "7", ctx)
        assertEquals("Review the environment rollback first.", page.feedback.message)
    }

    @Test fun `parked jobs explain artifact pipeline and prerequisite requirements with readable fallbacks`() {
        val model = model(jobStatus = "QUEUED")
        val run = model.read.runs.single()
        val job = run.jobs.single()
        val waiting = job.copy(awaitingRequirements = true,
            requirements = json("""[{"type":"raw","namespace":"releases","coordinate":"app:1"},"legacy",7,{},null]"""),
            pipelineRequirements = json("""[{"repository":"source","pipeline":"deploy","ref":"refs/heads/main"},{"pipeline":"build"}]"""))
        assertEquals("Waiting for raw:releases:app:1, legacy, 7, {}, null, source/deploy@refs/heads/main, /build@this ref", model.parkedReason(run, waiting))
        assertEquals("Waiting for requirements", model.parkedReason(run, job.copy(awaitingRequirements = true)))
        assertEquals("Waiting for requirements", model.parkedReason(run, job.copy(awaitingRequirements = true, requirements = JsonObject(emptyMap()))))
        assertEquals("Awaiting approval", model.parkedReason(run, waiting.copy(awaitingApproval = true)))
        val completed = job.copy(name = "ready", status = GitPipelineRunStatus.SUCCESS)
        assertEquals("Waiting for missing", model.parkedReason(run.copy(jobs = listOf(completed)), job.copy(dependsOn = listOf("ready", "missing"))))
        assertEquals("", model.parkedReason(run.copy(jobs = listOf(completed)), job.copy(dependsOn = listOf("ready"))))
        assertEquals("", model.parkedReason(run, job.copy(status = GitPipelineRunStatus.SUCCESS)))
    }

    private fun json(s: String) = Json.parseToJsonElement(s)
    private fun cross(field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject("crossProject") { put(field, value) } } }
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun native(status: String = "SUCCESS", jobStatus: String = status, awaiting: Boolean = false) = json("""{"git":{"pipelineRun":{
      "id":"run","pipelineId":"pipeline","repositoryId":"repo","ref":"refs/heads/main","triggerType":"RELEASE","status":"$status","number":1,"parameters":{},"created":"2027-01-01T00:00:00Z","started":null,"finished":null,"durationSeconds":null,
      "jobs":[{"id":"job","name":"Build","status":"$jobStatus","dependsOn":[],"requirements":null,"pipelineRequirements":null,"requirementsDeadline":null,"awaitingRequirements":false,"environment":"testing","approvalRequired":true,"approvedAt":null,"approvalComment":null,"awaitingApproval":$awaiting,"errorMessage":null,"attempt":1,"started":null,"finished":null,"steps":[]}]
    }}}""")
    private fun model(status: String = "SUCCESS", jobStatus: String = status, awaiting: Boolean = false, manage: Boolean = true, edit: Boolean = true, execute: Boolean = true, environment: Boolean = true): WorkOpsReleaseCiModel {
        val plans = WorkOpsReleasePlans.decodeData(cross("releasePlans", json("""[
          {"pipelineId":"pipeline","pipelineName":"Release","repositoryId":"repo","repositorySlug":"source","triggerType":"RELEASE","environment":null,"promotesFrom":null,"inputs":[
            {"name":"deploy.enabled","type":"boolean","defaultValue":"false","description":null,"options":[],"required":false},
            {"name":"replicas","type":"number","defaultValue":"0","description":null,"options":[],"required":false},
            {"name":"target","type":"string","defaultValue":null,"description":null,"options":[],"required":true}
          ],"jobs":[{"key":"build","environment":"testing","approvalRequired":true,"needs":[],"requirements":[],"depth":0,"steps":[]}]},
          {"pipelineId":"pipeline","pipelineName":"Release","repositoryId":"repo","repositorySlug":"source","triggerType":"PROMOTION","environment":"production","promotesFrom":"testing","inputs":[],"jobs":[]}
        ]"""))).workOps.crossProject.releasePlans
        val access = WorkOpsReleaseExecutionAccess.decodeData(cross("releaseActionAccess", json("""{"canManage":$manage,"repositories":[{"repositoryId":"repo","canEdit":$edit,"canExecute":$execute}],"environments":[{"key":"testing","canExecute":$environment},{"key":"production","canExecute":$environment}]}"""))).workOps.crossProject.releaseActionAccess
        val summaries = WorkOpsReleaseRunSummaries.decodeData(cross("releaseRuns", json("""[{"runId":"run","status":"$status","startedAt":"2027-01-01T00:00:00Z","finishedAt":null,"durationMs":null}]"""))).workOps.crossProject.releaseRuns
        return WorkOpsReleaseCiModel("release", WorkOpsReleaseCiReadModel("release", release = WorkOpsReleaseRecord("release", "program", "Release", version = 7),
            components = listOf(WorkOpsReleaseComponentRecord("project", "version", projectKey = "APP", projectName = "Application")), componentsLoaded = true,
            plans = plans, plansLoaded = true, summaries = summaries, summariesLoaded = true,
            runs = listOf(requireNotNull(WorkOpsReleaseNativeRun.decodeData(native(status, jobStatus, awaiting)).git.pipelineRun)),
            access = access, accessLoaded = true, readinessLoaded = true))
    }
    private val values = """{"input0":false,"input1":0,"input2":"Chosen target"}"""

    @Test fun `launch captures declarations and values and does not repeat an accepted write when refresh fails`() = runTest {
        val model = model(); model.openStart(); val captured = model.launchInputs.inputs
        model.read.plans = emptyList()
        var writes = 0; var deny = true
        val ctx = context { operation, variables -> when (operation) {
            "WorkOpsLaunchRelease" -> {
                writes++; assertEquals("release", variables.getValue("id").jsonPrimitive.content)
                assertEquals(JsonObject(mapOf("deploy.enabled" to JsonPrimitive("false"), "replicas" to JsonPrimitive("0"), "target" to JsonPrimitive("Chosen target"))), variables["inputs"])
                if (deny) error("Manage denied") else cross("launchRelease", JsonPrimitive(true))
            }
            else -> error("Refresh offline")
        } }
        model.launch(values, false, ctx)
        assertTrue(model.feedback.failed); assertNotNull(model.launching); assertEquals(captured, model.launchInputs.inputs)
        assertEquals(json(values), model.launchValues); assertEquals(0, model.revision)
        deny = false; model.launch(values, false, ctx)
        assertFalse(model.feedback.failed); assertNull(model.launching); assertTrue(model.read.feedback.failed); assertEquals(1, model.revision)
        model.launch(values, false, ctx); assertTrue(model.feedback.failed); assertEquals(2, writes)
    }

    @Test fun `input validation and false launch acknowledgements retain the dialog without claiming success`() = runTest {
        val model = model(); model.openStart(); var writes = 0
        val ctx = context { operation, _ -> assertEquals("WorkOpsLaunchRelease", operation); writes++; cross("launchRelease", JsonPrimitive(false)) }
        model.launch("""{"input0":false,"input1":0,"input2":""}""", false, ctx)
        assertTrue(model.feedback.failed); assertEquals(0, writes); assertNotNull(model.launching)
        model.launch(values, false, ctx); assertTrue(model.feedback.failed); assertEquals(1, writes); assertNotNull(model.launching); assertEquals(0, model.revision)
    }

    @Test fun `promotion retains the exact environment inputs and downgrade choice after an execution rejection`() = runTest {
        val model = model(); model.openPromotion("production"); assertNotNull(model.launching)
        model.launch("{}", true, context { operation, variables ->
            assertEquals("WorkOpsPromoteRelease", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(JsonPrimitive("production"), input["environment"]); assertEquals(JsonPrimitive(true), input["allowDowngrade"]); assertEquals(JsonObject(emptyMap()), input["inputs"])
            error("Environment execute denied")
        })
        assertTrue(model.feedback.failed); assertEquals("production", model.launching?.environment); assertEquals(true, model.launching?.allowDowngrade)
        model.launch("{}", true, context { operation, _ -> if (operation == "WorkOpsPromoteRelease") cross("promoteRelease", JsonPrimitive(true)) else error("Read offline") })
        assertNull(model.launching); assertFalse(model.feedback.failed); assertTrue(model.read.feedback.failed)
    }

    @Test fun `patch selection retains denied choices deduplicates projects and stores the actual new release destination`() = runTest {
        val model = model(); model.read.components = model.read.components + model.read.components.single().copy(versionId = "second")
        model.openPatch(); assertEquals(1, model.patchProjects.size)
        val raw = """{"projects":["project","project"]}"""
        model.patch(raw, context { _, _ -> error("Manage denied") }); assertTrue(model.patching); assertEquals(json(raw), model.patchValues)
        model.patch(raw, context { operation, variables ->
            assertEquals("WorkOpsPatchRelease", operation); assertEquals(JsonArray(listOf(JsonPrimitive("project"))), variables.getValue("input").jsonObject["projectIds"])
            cross("startPatchRelease", json("""{"id":"patch","programId":"program","name":"Patch","description":null,"releaseDate":null,"releasedAt":null,"version":0}"""))
        })
        assertFalse(model.patching); assertEquals("/workops/releases/patch", model.nextHref); assertFalse(model.feedback.failed)
    }

    @Test fun `job reruns require execute and a terminal run but do not require edit or program management`() = runTest {
        val model = model(status = "FAILURE", manage = false, edit = false)
        model.openControl("rerunJob", "run", "job"); assertNotNull(model.confirming)
        model.control(true, context { operation, variables -> when (operation) {
            "WorkOpsReleaseNativeRun" -> native("FAILURE")
            "WorkOpsRerunReleaseJob" -> { assertEquals(JsonPrimitive("job"), variables["id"]); json("""{"git":{"rerunPipelineJob":{"id":"job","status":"QUEUED"}}}""") }
            else -> error("Read offline")
        } })
        assertNull(model.confirming); assertFalse(model.feedback.failed); assertTrue(model.read.feedback.failed)
        val active = model(status = "RUNNING", jobStatus = "FAILURE")
        active.openControl("rerunJob", "run", "job"); assertTrue(active.feedback.failed); assertNull(active.confirming)
        val denied = model(status = "FAILURE", execute = false)
        denied.openControl("rerunJob", "run", "job"); assertTrue(denied.feedback.message.contains("Execute permission"))
    }

    @Test fun `confirmed run and job cancellation use inspected identities and edit independently of execute`() = runTest {
        for (kind in listOf("cancelRun", "cancelJob")) {
            val model = model(status = "RUNNING", manage = false, execute = false)
            model.openControl(kind, "run", if (kind == "cancelJob") "job" else "")
            model.control(false, context { _, _ -> error("Unconfirmed write") }); assertNotNull(model.confirming)
            var writes = 0
            model.control(true, context { operation, variables -> when (operation) {
                "WorkOpsReleaseNativeRun" -> native("RUNNING")
                "WorkOpsCancelReleaseRun", "WorkOpsCancelReleaseJob" -> {
                    writes++; val target = if (kind == "cancelJob") "job" else "run"; assertEquals(JsonPrimitive(target), variables["id"])
                    val field = if (kind == "cancelJob") "cancelPipelineJob" else "cancelPipelineRun"
                    json("""{"git":{"$field":{"id":"$target","status":"CANCELLED"}}}""")
                }
                else -> error("Read offline")
            } })
            assertEquals(1, writes); assertNull(model.confirming); assertFalse(model.feedback.failed)
        }
    }

    @Test fun `approval and rejection preserve failed comments and require repository and environment execute only`() = runTest {
        for (approve in listOf(true, false)) {
            val model = model(status = "QUEUED", awaiting = true, manage = false, edit = false)
            val operation = if (approve) "WorkOpsApproveReleaseJob" else "WorkOpsRejectReleaseJob"
            var deny = true
            val ctx = context { op, variables -> when (op) {
                "WorkOpsReleaseNativeRun" -> native("QUEUED", awaiting = true)
                operation -> {
                    assertEquals(JsonPrimitive("job"), variables["id"]); assertEquals(JsonPrimitive("Review retained"), variables["comment"])
                    if (deny) error("Execution revoked")
                    val field = if (approve) "approvePipelineJob" else "rejectPipelineJob"
                    json("""{"git":{"$field":{"id":"job","status":"${if (approve) "SUCCESS" else "FAILURE"}"}}}""")
                }
                else -> error("Read offline")
            } }
            model.decide("job", " Review retained ", approve, ctx)
            assertTrue(model.feedback.failed); assertEquals(" Review retained ", model.comments["job"])
            deny = false; model.decide("job", " Review retained ", approve, ctx)
            assertFalse(model.feedback.failed); assertFalse("job" in model.comments); assertTrue(model.read.feedback.failed)
        }
        val denied = model(status = "QUEUED", awaiting = true, environment = false)
        denied.decide("job", "Retained", true, context { operation, _ -> assertEquals("WorkOpsReleaseNativeRun", operation); native("QUEUED", awaiting = true) })
        assertTrue(denied.feedback.message.contains("Execute permission on testing")); assertEquals("Retained", denied.comments["job"])
    }

    @Test fun `rollback confirmation retains rejected targets and valid native revisions without repeating successful writes`() = runTest {
        val attempt = model(edit = false, execute = false); attempt.openAttemptRollback()
        attempt.rollbackAttempt(false, context { _, _ -> error("Unconfirmed") }); assertNotNull(attempt.confirming)
        attempt.rollbackAttempt(true, context { operation, _ -> assertEquals("WorkOpsRollbackReleaseAttempt", operation); cross("rollbackReleaseArtifacts", JsonPrimitive(false)) })
        assertNotNull(attempt.confirming); assertTrue(attempt.feedback.failed)
        attempt.rollbackAttempt(true, context { operation, _ -> if (operation == "WorkOpsRollbackReleaseAttempt") cross("rollbackReleaseArtifacts", JsonPrimitive(true)) else error("Read offline") })
        assertNull(attempt.confirming); assertFalse(attempt.feedback.failed); assertTrue(attempt.read.feedback.failed)
        val environment = model(); environment.openEnvironmentRollback("testing")
        environment.rollbackEnvironment(true, "-1", context { _, _ -> error("Invalid revision") }); assertEquals("-1", environment.rollbackRevisionDraft); assertNotNull(environment.confirming)
        var deny = true
        val ctx = context { operation, variables -> when (operation) {
            "WorkOpsRollbackReleaseEnvironment" -> {
                assertEquals(JsonPrimitive("testing"), variables["environment"]); assertEquals(JsonPrimitive(3), variables["revision"])
                if (deny) error("Target execution denied")
                cross("rollbackReleaseEnvironment", JsonArray(listOf(JsonPrimitive("target/revision/3"))))
            }
            else -> error("Read offline")
        } }
        environment.rollbackEnvironment(true, "3", ctx); assertEquals("3", environment.rollbackRevisionDraft); assertNotNull(environment.confirming)
        deny = false; environment.rollbackEnvironment(true, "3", ctx)
        assertNull(environment.confirming); assertFalse(environment.feedback.failed); assertEquals(listOf("target/revision/3"), environment.rollbackOutcomes)
    }

    @Test fun `cancellation of a launch propagates and keeps its input draft`() = runTest {
        val model = model(); model.openStart()
        assertFailsWith<CancellationException> { model.launch(values, false, context { _, _ -> throw CancellationException("Navigation") }) }
        assertNotNull(model.launching); assertEquals(json(values), model.launchValues)
    }
}
