package bosca.cli.mcp

import bosca.workops.model.spec.RequirementContentInput
import bosca.workops.model.spec.ReviewedInterface

import bosca.cli.api.ContentApi
import bosca.cli.api.NetworkClient
import bosca.cli.api.WorkOpsApi
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

class WorkOpsPlanToolRegistrarTest {
    @Test fun `requirement input shares parsed heading validation with the backend`() {
        for (body in listOf("## Acceptance criteria ##", "Acceptance criteria\n---", "## ~~Acceptance~~ criteria")) {
            assertFailsWith<IllegalArgumentException> { RequirementContentInput("Title", body, listOf("Pass")).validate() }
        }
        RequirementContentInput("Title", "```markdown\n## Acceptance criteria\n```", listOf("Pass")).validate()
    }

    @Test
    fun `soft deleted spec and requirement documents block verification and evidence recording until restored`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.record()
            for (deletedId in listOf(SPEC_METADATA_ID, fixture.requirements.single().metadataId)) {
                fixture.transformResponse = { request, value ->
                    if (request.operation() == "GetMetadataDocumentContext" && request.variables().getValue("id") == JsonPrimitive(deletedId)) {
                        val metadata = value.getValue("content").jsonObject.getValue("metadata").jsonObject
                        obj("content" to obj("metadata" to JsonObject(metadata + obj("deleted" to true))))
                    } else value
                }
                val verified = fixture.verify()
                assertFalse(verified.ready(), verified.toString())
                assertTrue(verified.issues().any { "document" in it.lowercase() })
                val before = fixture.comments.size
                assertFailsWith<IllegalStateException> { fixture.record() }
                assertEquals(before, fixture.comments.size)
                fixture.transformResponse = { _, value -> value }
                assertTrue(fixture.verify().ready())
            }
        }
    }

    @Test
    fun `instructions load live project guidance and disabled policy is explicit in context`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val instructions = fixture.handle(obj("action" to "instructions", "projectId" to PROJECT_ID)).parseObject()
            assertEquals("initial-guide", instructions["revision"]?.jsonPrimitive?.content)
            assertEquals("# Custom workflow", instructions.getValue("guides").jsonArray.single().jsonObject["markdown"]?.jsonPrimitive?.content)
            fixture.guidancePolicy = obj("requireInterfaceReview" to false, "requireTdd" to false, "requireTestQuality" to false,
                "minimumLineCoverage" to 99.0, "minimumBranchCoverage" to 97.0)
            fixture.handle(obj("action" to "record_validation", "id" to SPEC_ID, "requirementId" to fixture.requirements.single().id,
                "checks" to JsonArray(listOf(obj("criterion" to CRITERION, "outcome" to "PASSED", "evidence" to "Observed the acceptance behavior")))))
            val verified = fixture.verify()
            assertTrue(verified.ready(), verified.issues().toString())
            assertFalse(verified.getValue("guidance").jsonObject.getValue("policy").jsonObject.getValue("requireTestQuality").jsonPrimitive.boolean)
            assertEquals(JsonNull, verified["interfaceReview"])
            fixture.guidanceRevision = "new-instructions"
            assertEquals("new-instructions", fixture.handle(obj("action" to "instructions")).parseObject()["revision"]?.jsonPrimitive?.content)
            fixture.failOperation = "GetEffectiveWorkOpsGuidance"
            assertFailsWith<Exception> { fixture.handle(obj("action" to "instructions")) }
        }
    }

    @Test
    fun `guidance changes invalidate reviewed interfaces and validation`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.record()
            assertTrue(fixture.verify().ready())
            fixture.guidanceRevision = "changed-guide"
            val stale = fixture.verify()
            assertFalse(stale.ready())
            assertTrue(stale.issues().any { "guidance" in it.lowercase() }, stale.issues().toString())
            fixture.handle(interfaceReviewArgs())
            assertFalse(fixture.verify().ready())
            fixture.record()
            assertTrue(fixture.verify().ready())
        }
    }

    @Test
    fun `effective policy governs the same passing validation and verification checks`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.guidancePolicy = obj("requireInterfaceReview" to false, "requireTdd" to false, "requireTestQuality" to true,
                "minimumLineCoverage" to 90.0, "minimumBranchCoverage" to 80.0)
            val measured = qualityEvidence().getValue("coverage").jsonArray.single().jsonObject
            val quality = JsonObject(qualityEvidence() + ("coverage" to JsonArray(listOf(JsonObject(measured + obj(
                "lineCovered" to 900, "lineMissed" to 100, "branchCovered" to 800, "branchMissed" to 200))))))
            fixture.handle(obj("action" to "record_validation", "id" to SPEC_ID, "requirementId" to fixture.requirements.single().id,
                "testQuality" to quality, "checks" to JsonArray(listOf(obj("criterion" to CRITERION, "outcome" to "PASSED", "evidence" to "Behavior assertions pass")))))
            assertTrue(fixture.verify().ready())
            fixture.guidancePolicy = JsonObject(fixture.guidancePolicy + obj("minimumLineCoverage" to 95.0))
            assertFalse(fixture.verify().ready())
        }
    }

    @Test
    fun `coverage summaries distinguish no branches from unmeasured and handle large counters`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.handle(interfaceReviewArgs())
            val measured = qualityEvidence().getValue("coverage").jsonArray.single().jsonObject
            val quality = JsonObject(qualityEvidence() + ("coverage" to JsonArray(listOf(
                JsonObject(measured + obj("scope" to "Straight-through production function", "lineCovered" to 2, "lineMissed" to 0,
                    "branchCovered" to 0, "branchMissed" to 0, "noBranchesReason" to "Instrumented report has no conditional branches in this scope", "uncovered" to "None")),
                JsonObject(measured + obj("scope" to "Large report", "lineCovered" to Long.MAX_VALUE, "lineMissed" to 0,
                    "branchCovered" to Long.MAX_VALUE, "branchMissed" to 0, "uncovered" to "None")),
            ))))
            fixture.handle(obj("action" to "record_validation", "id" to SPEC_ID, "requirementId" to fixture.requirements.single().id,
                "testQuality" to quality, "checks" to JsonArray(listOf(obj("criterion" to CRITERION, "outcome" to "PASSED", "evidence" to "Behavior assertions pass", "tdd" to tddEvidence())))))
            val verified = fixture.verify()
            assertTrue(verified.ready(), verified.issues().toString())
            val summaries = verified.getValue("requirements").jsonArray.single().jsonObject.getValue("coverageSummary").jsonArray
            assertEquals(JsonNull, summaries[0].jsonObject["branchPercent"])
            assertEquals(100.0, summaries[0].jsonObject.getValue("linePercent").jsonPrimitive.double)
            assertEquals(100.0, summaries[1].jsonObject.getValue("branchPercent").jsonPrimitive.double)
        }
    }

    @Test
    fun `passing validation requires reviewed tests and measured high line and branch coverage`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.handle(interfaceReviewArgs())
            suspend fun record(quality: JsonElement?) = fixture.handle(obj("action" to "record_validation", "id" to SPEC_ID,
                "requirementId" to fixture.requirements.single().id, "checks" to JsonArray(listOf(obj("criterion" to CRITERION,
                    "outcome" to "PASSED", "evidence" to "Assertions pass", "tdd" to tddEvidence()))),
                *if (quality == null) emptyArray() else arrayOf("testQuality" to quality)))
            assertFailsWith<IllegalArgumentException> { record(null) }
            val measured = qualityEvidence().getValue("coverage").jsonArray.single().jsonObject
            for (counters in listOf(obj("lineCovered" to 989, "lineMissed" to 11), obj("branchCovered" to 969, "branchMissed" to 31), obj("lineCovered" to 98999, "lineMissed" to 1001),
                obj("lineCovered" to 0, "lineMissed" to 0), obj("lineCovered" to -1), obj("lineMissed" to -1), obj("branchCovered" to -1), obj("branchMissed" to -1),
                obj("branchCovered" to 0, "branchMissed" to 0))) {
                val quality = JsonObject(qualityEvidence() + ("coverage" to JsonArray(listOf(JsonObject(measured + counters)))))
                assertFailsWith<IllegalArgumentException> { record(quality) }
            }
            assertFailsWith<IllegalArgumentException> { record(JsonObject(qualityEvidence() + ("review" to JsonPrimitive(" ")))) }
            assertFailsWith<IllegalArgumentException> { record(JsonObject(qualityEvidence() + ("coverage" to JsonArray(emptyList())))) }
            for (field in listOf("scope", "command", "report", "uncovered")) {
                assertFailsWith<IllegalArgumentException> { record(JsonObject(qualityEvidence() + ("coverage" to JsonArray(listOf(JsonObject(measured + (field to JsonPrimitive(" ")))))))) }
            }
            assertEquals(1, fixture.comments.size, "Rejected evidence must not write comments")
            record(qualityEvidence())
            assertTrue(fixture.verify().ready())
            val validation = fixture.verify()["requirements"]?.jsonArray?.single()?.jsonObject?.get("validation")?.jsonObject
            assertEquals(990, validation?.get("testQuality")?.jsonObject?.get("coverage")?.jsonArray?.single()?.jsonObject?.get("lineCovered")?.jsonPrimitive?.int)
        }
    }

    @Test
    fun `verification refuses old passing records without coverage or with a failing scope`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.record()
            val evidence = fixture.comments.last()
            val payload = evidence.getValue("content").jsonPrimitive.content.removePrefix("## WorkOps validation\n\n```json\n").removeSuffix("\n```").parseObject()
            for (quality in listOf(JsonNull, JsonObject(qualityEvidence() + ("coverage" to JsonArray(listOf(
                qualityEvidence().getValue("coverage").jsonArray.single(),
                JsonObject(qualityEvidence().getValue("coverage").jsonArray.single().jsonObject + obj("scope" to "Other affected module", "branchCovered" to 90, "branchMissed" to 10)),
            )))))) {
                val changed = JsonObject(payload + ("testQuality" to quality))
                fixture.comments[fixture.comments.lastIndex] = JsonObject(evidence + ("content" to JsonPrimitive("## WorkOps validation\n\n```json\n$changed\n```")))
                val verified = fixture.verify()
                assertFalse(verified.ready())
                assertTrue(verified.issues().any { "coverage" in it || "test quality" in it })
            }
        }
    }

    @Test
    fun `implementation requires a separate current interface review`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.requirements.single().category = "TODO"
            fixture.handle(obj("action" to "review", "id" to SPEC_ID))
            assertEquals("DESIGN_INTERFACES", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()["action"]?.jsonPrimitive?.content)
            val reviewed = fixture.handle(interfaceReviewArgs()).parseObject()
            assertTrue(reviewed.getValue("recorded").jsonPrimitive.boolean)
            assertEquals("START_TASK", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()["action"]?.jsonPrimitive?.content)
            fixture.specBody += " revised contract"
            fixture.handle(obj("action" to "review", "id" to SPEC_ID))
            assertEquals("DESIGN_INTERFACES", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()["action"]?.jsonPrimitive?.content)
            assertTrue(fixture.requests.none { it.operation().startsWith("Transition") })
        }
    }

    @Test
    fun `interface review rejects empty declarations and preserves the latest reviewed contract`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            for (interfaces in listOf(JsonArray(emptyList()), JsonArray(listOf(obj("path" to "", "declaration" to "interface Search"))),
                JsonArray(listOf(obj("path" to "Search.kt", "declaration" to ""))))) {
                assertFailsWith<IllegalArgumentException> { fixture.handle(JsonObject(interfaceReviewArgs() + ("interfaces" to interfaces))) }
            }
            assertTrue(fixture.comments.isEmpty())
            fixture.handle(interfaceReviewArgs())
            fixture.handle(obj("action" to "review", "id" to SPEC_ID))
            assertEquals("VERIFY", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()["action"]?.jsonPrimitive?.content)
            assertTrue(fixture.comments.first()["content"]?.jsonPrimitive?.content.orEmpty().contains("suspend fun search"))
            fixture.comments[0] = JsonObject(fixture.comments[0] + ("deleted" to JsonPrimitive(true)))
            assertEquals("DESIGN_INTERFACES", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()["action"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `passing validation requires failed red and passing green and refactor evidence`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val check = obj("criterion" to CRITERION, "outcome" to "PASSED", "evidence" to "Tests pass")
            suspend fun record(tdd: JsonElement?) = fixture.handle(obj("action" to "record_validation", "id" to SPEC_ID,
                "requirementId" to fixture.requirements.single().id, "testQuality" to qualityEvidence(), "checks" to JsonArray(listOf(if (tdd == null) check else JsonObject(check + ("tdd" to tdd))))))
            assertFailsWith<IllegalArgumentException> { record(null) }
            for (phase in listOf("red", "green", "refactor")) {
                val run = tddEvidence().getValue(phase).jsonObject
                val wrongOutcome = if (phase == "red") "PASSED" else "FAILED"
                assertFailsWith<IllegalArgumentException> { record(JsonObject(tddEvidence() + (phase to JsonObject(run + ("outcome" to JsonPrimitive(wrongOutcome)))))) }
                for (field in listOf("command", "evidence")) {
                    assertFailsWith<IllegalArgumentException> { record(JsonObject(tddEvidence() + (phase to JsonObject(run + (field to JsonPrimitive(" ")))))) }
                }
            }
            assertTrue(fixture.comments.isEmpty())
            assertFailsWith<IllegalArgumentException> { record(tddEvidence()) }
            assertFalse(fixture.verify().ready(), "A TDD report alone must not bypass interface review")
            fixture.handle(interfaceReviewArgs())
            record(tddEvidence())
            assertTrue(fixture.verify().ready())
        }
    }

    @Test
    fun `verification rejects legacy passing evidence without a TDD cycle`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.handle(interfaceReviewArgs())
            fixture.record()
            val evidence = fixture.comments.last()
            val content = evidence.getValue("content").jsonPrimitive.content
            val payload = content.removePrefix("## WorkOps validation\n\n```json\n").removeSuffix("\n```").parseObject()
            val check = payload.getValue("checks").jsonArray.single().jsonObject
            val legacy = JsonObject(payload + ("checks" to JsonArray(listOf(JsonObject(check - "tdd")))))
            fixture.comments[fixture.comments.lastIndex] = JsonObject(evidence + ("content" to JsonPrimitive("## WorkOps validation\n\n```json\n$legacy\n```")))
            assertTrue(fixture.verify().issues().any { "TDD" in it })
            assertFalse(fixture.verify().ready())
        }
    }

    @Test
    fun `MCP discovery and calls expose complete authenticated planning and spec transitions`() = runBlocking<Unit> {
        Fixture(initialRequirements = 0).use { fixture ->
            val server = Server(Implementation("test", "1"), ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))))
            McpToolRegistrar.registerAll(server, fixture.api, fixture.content)
            val transport = LoopbackTransport()
            server.createSession(transport)
            try {
                transport.request("initialize", obj("protocolVersion" to "2025-06-18", "capabilities" to obj(), "clientInfo" to obj("name" to "test", "version" to "1")))
                transport.receive(JSONRPCNotification("notifications/initialized"))
                val tools = assertIs<ListToolsResult>(transport.request("tools/list"))
                assertTrue(tools.tools.any { it.name == "workops_plan" })
                val specActions = tools.tools.first { it.name == "workops_spec" }.inputSchema.properties?.get("action")?.jsonObject?.get("enum")?.jsonArray.orEmpty()
                assertTrue(JsonPrimitive("transitions") in specActions)

                assertTrue(assertIs<CallToolResult>(transport.request("tools/call", obj("name" to "workops_plan"))).isError == true)
                val plan = transport.call("workops_plan", createArgs())
                assertFalse(plan.isError == true, plan.text())
                assertEquals(SPEC_ID, plan.output().getValue("specId").jsonPrimitive.content)
                val requirement = fixture.requirements.single()
                assertTrue(requirement.body.contains("## Acceptance criteria"))
                assertEquals(requirement.body, requirement.description)
                assertTrue(plan.output().getValue("atomic").jsonPrimitive.boolean)
                assertEquals(1, fixture.requests.count { it.operation() == "CreateWorkOpsSpecPlan" })
                assertTrue(fixture.requests.none { it.operation() in listOf("SetMetadataMarkdown", "UpdateWorkOpsTask") })

                val transitions = transport.call("workops_spec", obj("action" to "transitions", "id" to SPEC_ID))
                assertFalse(transitions.isError == true, transitions.text())
                assertEquals("IN_PROGRESS", Json.parseToJsonElement(transitions.text()).jsonArray.single().jsonObject.getValue("toStatusCategory").jsonPrimitive.content)
                val context = transport.call("workops_plan", obj("action" to "context", "id" to SPEC_ID)).output()
                assertEquals(CRITERION, context.getValue("requirements").jsonArray.single().jsonObject.getValue("acceptanceCriteria").jsonArray.single().jsonPrimitive.content)
                assertTrue(context.getValue("spec").jsonObject.getValue("contexts").jsonArray.isNotEmpty())

                val edit = transport.call("workops_requirement", obj("action" to "set_content", "id" to requirement.id,
                    "name" to "Revised requirement", "markdownContent" to "Revised behavior", "acceptanceCriteria" to array(CRITERION)))
                assertFalse(edit.isError == true, edit.text())
                assertEquals("Revised requirement", requirement.summary)
                assertTrue(requirement.description.contains("Revised behavior"))
                assertFalse(transport.call("workops_plan", interfaceReviewArgs()).isError == true)
                val evidence = transport.call("workops_plan", obj("action" to "record_validation", "id" to SPEC_ID,
                    "requirementId" to requirement.id, "testQuality" to qualityEvidence(), "checks" to JsonArray(listOf(obj("criterion" to CRITERION,
                        "outcome" to "PASSED", "evidence" to "Focused regression assertions passed", "tdd" to tddEvidence())))))
                assertFalse(evidence.isError == true, evidence.text())
                val verified = transport.call("workops_plan", obj("action" to "verify", "id" to SPEC_ID))
                assertFalse(verified.isError == true, verified.text())
                assertTrue(verified.output().ready(), verified.text())
                assertTrue(fixture.authorizations.all { it == "Bearer test-token" })
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun `invalid later requirement is rejected before any plan is persisted`() = runBlocking<Unit> {
        Fixture(initialRequirements = 0).use { fixture ->
            val args = JsonObject(createArgs() + ("requirements" to JsonArray(listOf(
                requirementInput(), requirementInput(criteria = emptyList()),
            ))))
            assertFailsWith<IllegalArgumentException> { fixture.handle(args) }
            assertTrue(fixture.requests.isEmpty())
            assertTrue(fixture.requirements.isEmpty())
        }
    }

    @Test
    fun `failed atomic create leaves no records and identical retry returns existing plan`() = runBlocking<Unit> {
        Fixture(initialRequirements = 0).use { fixture ->
            fixture.failOperation = "CreateWorkOpsSpecPlan"
            val failure = WorkOpsPlanToolRegistrar.result { fixture.handle(createArgs()) }
            assertTrue(failure.isError == true)
            assertTrue(fixture.requirements.isEmpty())
            fixture.failOperation = null
            val first = fixture.handle(createArgs()).parseObject()
            val second = fixture.handle(createArgs()).parseObject()
            assertEquals(first, second)
            assertEquals(1, fixture.requirements.size)
        }
    }

    @Test
    fun `requirement revisions replace both bodies atomically and failed writes can be retried`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val requirement = fixture.requirements.single()
            fixture.record()
            val args = obj("action" to "update_requirement", "id" to SPEC_ID, "requirementId" to requirement.id,
                "name" to "Revised requirement", "markdownContent" to "New implementation detail", "acceptanceCriteria" to array("New criterion"))
            fixture.failOperation = "SetWorkOpsRequirementContent"
            val failure = WorkOpsPlanToolRegistrar.result { fixture.handle(args) }
            assertTrue(failure.isError == true)
            assertEquals("Requirement 0", requirement.summary)
            assertEquals("Implement behavior", requirement.body)
            fixture.failOperation = null
            fixture.handle(args)
            assertEquals(1, fixture.requirements.size)
            assertEquals("Revised requirement", requirement.summary)
            assertTrue(requirement.description.contains("New implementation detail"))
            val context = fixture.handle(obj("action" to "context", "id" to SPEC_ID)).parseObject()
            assertEquals("New criterion", context.getValue("requirements").jsonArray.single().jsonObject.getValue("acceptanceCriteria").jsonArray.single().jsonPrimitive.content)
            assertTrue(context.issues().any { "Validation evidence is stale" in it })
        }
    }

    @Test
    fun `adding a requirement fills its task and plan updates preserve the document title`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.specTitle = "Current document title"
            fixture.handle(obj("action" to "update", "id" to SPEC_ID, "markdownContent" to "Revised plan"))
            val specWrite = fixture.requests.last { it.operation() == "SetMetadataMarkdown" }.variables()
            assertEquals("Current document title", specWrite.getValue("title").jsonPrimitive.content)
            val args = obj("action" to "add_requirement", "requestId" to uuid(999), "id" to SPEC_ID, "name" to "Another deliverable",
                "markdownContent" to "Additional behavior", "acceptanceCriteria" to array("Second criterion"))
            val added = fixture.handle(args).parseObject()
            assertEquals(fixture.requirements.last().id, added.getValue("requirementId").jsonPrimitive.content)
            assertTrue(fixture.requirements.last().description.contains("Second criterion"))
            assertTrue(fixture.requests.none { it.operation().startsWith("Transition") })
        }
    }

    @Test
    fun `done statuses alone do not satisfy plan verification`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val output = fixture.verify()
            assertFalse(output.ready())
            assertTrue(output.issues().any { "Validation evidence is missing" in it })
            assertTrue(fixture.requests.none { it.operation().startsWith("Transition") })
        }
    }

    @Test
    fun `passing evidence for each criterion makes completed plan ready without changing statuses`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.record()
            val output = fixture.verify()
            assertTrue(output.ready(), output.issues().toString())
            assertTrue(output.issues().isEmpty())
            assertEquals(2, fixture.comments.size)
            assertTrue(fixture.comments.last().getValue("content").jsonPrimitive.content.contains("./gradlew :cli:test"))
            assertTrue(fixture.requests.none { it.operation().startsWith("Transition") })
        }
    }

    @Test
    fun `failed unrun or uncovered criteria prevent completion`() = runBlocking<Unit> {
        for (outcome in listOf("FAILED", "NOT_RUN")) {
            Fixture().use { fixture ->
                fixture.record(outcome = outcome)
                val output = fixture.verify()
                assertFalse(output.ready())
                assertTrue(output.issues().any { "Criterion lacks passing evidence" in it })
            }
        }
        Fixture().use { fixture ->
            fixture.requirements.single().criteria += "Failure remains visible"
            fixture.record()
            val output = fixture.verify()
            assertFalse(output.ready())
            assertTrue(output.issues().any { "Failure remains visible" in it })
        }
    }

    @Test
    fun `document and task changes invalidate prior evidence`() = runBlocking<Unit> {
        for (change in listOf<(Fixture) -> Unit>(
            { it.requirements.single().taskVersion++ },
            { it.requirements.single().body += "Changed behavior" },
            { it.specBody += "Changed plan" },
        )) {
            Fixture().use { fixture ->
                fixture.record()
                change(fixture)
                val output = fixture.verify()
                assertFalse(output.ready())
                assertTrue(output.issues().any { "Validation evidence is stale" in it })
            }
        }
    }

    @Test
    fun `latest failed evidence supersedes older passing evidence and deleted evidence is ignored`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.record()
            fixture.record(outcome = "FAILED")
            assertFalse(fixture.verify().ready())
            fixture.comments[2] = JsonObject(fixture.comments[2] + ("deleted" to JsonPrimitive(true)))
            assertTrue(fixture.verify().ready())
            fixture.comments[1] = JsonObject(fixture.comments[1] + ("content" to JsonPrimitive("## WorkOps validation\n\n```json\ninvalid\n```")))
            assertFalse(fixture.verify().ready())
        }
    }

    @Test
    fun `verification reads every requirement comment and generation page`() = runBlocking<Unit> {
        Fixture(initialRequirements = 3).use { fixture ->
            repeat(100) { fixture.addComment("Investigation note $it") }
            fixture.requirements.forEach { fixture.record(it.id) }
            repeat(103) { fixture.generations.add(obj("id" to uuid(1000 + it), "metadataVersion" to 1, "source" to "MANUAL", "generatedTaskIds" to array())) }
            val output = fixture.verify(limit = 1)
            assertTrue(output.ready(), output.issues().toString())
            assertEquals(3, output.getValue("requirements").jsonArray.size)
            assertEquals(104, output.getValue("comments").jsonArray.size)
            assertEquals(103, output.getValue("taskGenerations").jsonArray.size)
            val generationOffsets = fixture.requests.filter { it.operation() == "GetWorkOpsPlanGenerations" }.map { it.variables().getValue("offset").jsonPrimitive.long }
            assertEquals(listOf(0L, 1L, 101L), generationOffsets)
            val outputContext = fixture.handle(obj("action" to "context", "id" to SPEC_ID, "limit" to 1)).parseObject()
            assertEquals(1L, outputContext.getValue("nextOffset").jsonPrimitive.long)
            assertEquals(1L, outputContext.getValue("nextGenerationOffset").jsonPrimitive.long)
            assertFalse(outputContext.ready())
        }
    }

    @Test
    fun `incomplete child specs and generated tasks prevent completion`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.record()
            fixture.childCount = 1
            val childOutput = fixture.verify()
            assertFalse(childOutput.ready())
            assertTrue(childOutput.issues().contains("Child specs are incomplete"))
            fixture.childCount = 0
            fixture.generatedTask = fixture.taskJson(fixture.requirements.single(), category = "IN_PROGRESS")
            fixture.generations.add(obj("id" to uuid(1000), "metadataVersion" to 1, "source" to "MANUAL", "generatedTaskIds" to array(GENERATED_TASK_ID)))
            val output = fixture.verify()
            assertFalse(output.ready())
            assertTrue(output.issues().any { "Generated task" in it })
        }
    }

    @Test
    fun `missing or inaccessible documents report verification gaps even with prior evidence`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.record()
            fixture.missingDocument = fixture.requirements.single().metadataId
            val output = fixture.verify()
            assertFalse(output.ready())
            assertTrue(output.issues().any { "Document is missing" in it })
        }
    }

    @Test
    fun `cross-spec requirement edits and evidence are rejected without mutations`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.requirementParentId = uuid(99)
            assertFailsWith<IllegalArgumentException> { fixture.handle(obj("action" to "record_validation", "id" to SPEC_ID, "requirementId" to fixture.requirements.single().id, "testQuality" to qualityEvidence(),
                "checks" to JsonArray(listOf(obj("criterion" to CRITERION, "outcome" to "PASSED", "evidence" to "Tests pass", "tdd" to tddEvidence()))))) }
            assertFailsWith<IllegalArgumentException> {
                fixture.handle(obj("action" to "update_requirement", "id" to SPEC_ID,
                    "requirementId" to fixture.requirements.single().id, "name" to "Edited", "markdownContent" to "body", "acceptanceCriteria" to array(CRITERION)))
            }
            assertTrue(fixture.requests.none { it.operation().startsWith("Update") || it.operation().startsWith("Set") || it.operation().startsWith("Add") })
        }
    }

    @Test
    fun `criteria are normalized from rich document text and ordinary comments are not evidence`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.addComment("All tests passed!")
            assertFalse(fixture.verify().ready())
            val criteria = WorkOpsPlanToolRegistrar.acceptanceCriteria(documentTree("body", listOf(CRITERION, "Second condition")))
            assertEquals(listOf(CRITERION, "Second condition"), criteria)
        }
    }

    @Test
    fun `cancellation propagates through the MCP result wrapper`() = runBlocking<Unit> {
        assertFailsWith<CancellationException> {
            WorkOpsPlanToolRegistrar.result { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `review diff reports scope bodies criteria additions removals and explicit baselines`() = runBlocking<Unit> {
        Fixture(initialRequirements = 2).use { fixture ->
            val noReview = fixture.handle(obj("action" to "diff", "id" to SPEC_ID)).parseObject()
            assertFalse(noReview.getValue("hasReview").jsonPrimitive.boolean)
            val review = fixture.handle(obj("action" to "review", "id" to SPEC_ID, "reviewNote" to "Scope reviewed")).parseObject()
            val originalId = review.getValue("reviewId")
            assertFalse(fixture.handle(obj("action" to "diff", "id" to SPEC_ID)).parseObject().getValue("changedSinceReview").jsonPrimitive.boolean)
            fixture.specBody = "Updated scope"
            fixture.requirements[0].criteria = listOf("New criterion")
            fixture.requirements.removeAt(1)
            fixture.requirements.add(RequirementState(3))
            val changed = fixture.handle(obj("action" to "diff", "id" to SPEC_ID)).parseObject()
            val changes = changed.getValue("changes").jsonArray.map { it.jsonObject }
            assertTrue(changes.any { it["field"] == JsonPrimitive("document") && it["diff"]?.jsonPrimitive?.content?.contains("+ Updated scope") == true })
            assertTrue(changes.any { it["field"] == JsonPrimitive("acceptanceCriteria") })
            assertTrue(changes.any { it["kind"] == JsonPrimitive("ADDED") })
            assertTrue(changes.any { it["kind"] == JsonPrimitive("REMOVED") })
            fixture.handle(obj("action" to "review", "id" to SPEC_ID))
            assertFalse(fixture.handle(obj("action" to "diff", "id" to SPEC_ID)).parseObject().getValue("changedSinceReview").jsonPrimitive.boolean)
            assertTrue(fixture.handle(obj("action" to "diff", "id" to SPEC_ID, "reviewId" to originalId)).parseObject().getValue("changedSinceReview").jsonPrimitive.boolean)
            assertTrue(fixture.requests.none { it.operation().startsWith("Transition") })
        }
    }

    @Test
    fun `review ignores task status changes and fails on inaccessible documents or missing baseline`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.handle(obj("action" to "review", "id" to SPEC_ID))
            fixture.requirements.single().category = "TODO"
            fixture.requirements.single().taskVersion++
            assertFalse(fixture.handle(obj("action" to "diff", "id" to SPEC_ID)).parseObject().getValue("changedSinceReview").jsonPrimitive.boolean)
            fixture.missingDocument = fixture.requirements.single().metadataId
            assertFailsWith<IllegalStateException> { fixture.handle(obj("action" to "review", "id" to SPEC_ID)) }
            assertEquals(1, fixture.comments.size)
            fixture.missingDocument = null
            assertFailsWith<IllegalStateException> { fixture.handle(obj("action" to "diff", "id" to SPEC_ID, "reviewId" to 999)) }
        }
    }

    @Test
    fun `deleted malformed or foreign review comments are ignored`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.handle(obj("action" to "review", "id" to SPEC_ID))
            fixture.comments[0] = JsonObject(fixture.comments[0] + ("deleted" to JsonPrimitive(true)))
            fixture.addComment("## WorkOps review\n\n```json\ninvalid\n```")
            assertFalse(fixture.handle(obj("action" to "diff", "id" to SPEC_ID)).parseObject().getValue("hasReview").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `next action recommends reviewing new or changed plans and continuing current work first`() = runBlocking<Unit> {
        Fixture(initialRequirements = 2).use { fixture ->
            fun nextArgs() = obj("action" to "next_action", "id" to SPEC_ID)
            assertEquals("REVIEW", fixture.handle(nextArgs()).parseObject().getValue("action").jsonPrimitive.content)
            fixture.handle(interfaceReviewArgs())
            fixture.requirements[0].category = "TODO"
            fixture.requirements[1].category = "IN_PROGRESS"
            val continuing = fixture.handle(nextArgs()).parseObject()
            assertEquals("CONTINUE", continuing.getValue("action").jsonPrimitive.content)
            assertEquals(fixture.requirements[1].taskId, continuing.getValue("taskId").jsonPrimitive.content)
            fixture.requirements[1].category = "DONE"
            val start = fixture.handle(nextArgs()).parseObject()
            assertEquals("START_TASK", start.getValue("action").jsonPrimitive.content)
            assertEquals(uuid(800), start.getValue("suggestedArguments").jsonObject.getValue("transitionId").jsonPrimitive.content)
            fixture.specBody += " changed"
            assertEquals("REVIEW", fixture.handle(nextArgs()).parseObject().getValue("action").jsonPrimitive.content)
            assertTrue(fixture.requests.none { it.operation().startsWith("Transition") })
        }
    }

    @Test
    fun `next action respects incoming blockers and ignores outgoing completed or nonblocking links`() = runBlocking<Unit> {
        Fixture(initialRequirements = 2).use { fixture ->
            fixture.handle(interfaceReviewArgs())
            val a = fixture.requirements[0]; val b = fixture.requirements[1]
            a.category = "TODO"; b.category = "TODO"
            fixture.taskLinks[a.taskId] = listOf(blockingLink(b, a))
            val next = fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()
            assertEquals(b.taskId, next.getValue("taskId").jsonPrimitive.content)
            assertEquals(1, next.getValue("blockedTasks").jsonArray.size)
            b.category = "DONE"
            fixture.taskLinks[a.taskId] = listOf(blockingLink(b, a, "DONE"), blockingLink(a, b), blockingLink(b, a, "TODO", "RELATES_TO"))
            assertEquals(a.taskId, fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject().getValue("taskId").jsonPrimitive.content)
            fixture.taskLinks[a.taskId] = listOf(JsonObject(blockingLink(b, a) + ("sourceTask" to JsonNull)))
            assertEquals("UNBLOCK", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject().getValue("action").jsonPrimitive.content)
            fixture.taskLinks.clear(); a.allowStart = false
            assertEquals("UNBLOCK", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject().getValue("action").jsonPrimitive.content)
        }
    }

    @Test
    fun `next action considers generated tasks and completed cancelled specs`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.handle(interfaceReviewArgs())
            assertEquals("VERIFY", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject().getValue("action").jsonPrimitive.content)
            fixture.generatedTask = JsonObject(fixture.taskJson(fixture.requirements.single(), "TODO") + ("id" to JsonPrimitive(GENERATED_TASK_ID)))
            fixture.generations.add(obj("id" to uuid(1000), "metadataVersion" to 1, "source" to "MANUAL", "generatedTaskIds" to array(GENERATED_TASK_ID)))
            assertEquals(GENERATED_TASK_ID, fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject().getValue("taskId").jsonPrimitive.content)
            fixture.specCategory = "DONE"
            assertEquals("VERIFY", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject().getValue("action").jsonPrimitive.content)
            fixture.specCategory = "CANCELLED"
            assertEquals("REOPEN", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject().getValue("action").jsonPrimitive.content)
        }
    }

    @Test
    fun `review and recommendations read all pages and state reorder diff is explicit`() = runBlocking<Unit> {
        Fixture(initialRequirements = 101).use { fixture ->
            repeat(101) { fixture.addComment("note $it") }
            val review = fixture.handle(interfaceReviewArgs()).parseObject()
            assertEquals(101, review.getValue("requirementCount").jsonPrimitive.int)
            fixture.requirements.last().category = "TODO"
            val next = fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()
            assertEquals(fixture.requirements.last().taskId, next.getValue("taskId").jsonPrimitive.content)
            fixture.requirements.reverse()
            val diff = fixture.handle(obj("action" to "diff", "id" to SPEC_ID)).parseObject()
            assertTrue(diff.getValue("changes").jsonArray.any { it.jsonObject["field"] == JsonPrimitive("requirementOrder") })
        }
    }

    @Test
    fun `line diff reports changed spans without repeating identical prefix and suffix`() {
        assertEquals("- old\n+ new", WorkOpsPlanReview.lineDiff("same\nold\nend", "same\nnew\nend"))
        assertEquals("", WorkOpsPlanReview.lineDiff("same", "same"))
        assertEquals("+ second", WorkOpsPlanReview.lineDiff("first", "first\nsecond"))
    }

    private fun blockingLink(source: RequirementState, target: RequirementState, category: String = source.category, type: String = "BLOCKS") = obj(
        "id" to uuid(990), "linkType" to obj("category" to type, "name" to "Blocks"),
        "sourceTask" to obj("id" to source.taskId, "key" to "TASK-${source.index + 1}", "summary" to source.summary, "status" to status(category)),
        "targetTask" to obj("id" to target.taskId, "key" to "TASK-${target.index + 1}"),
    )

    private class LoopbackTransport : AbstractTransport() {
        private val responses = Channel<JSONRPCMessage>(Channel.UNLIMITED)
        private var nextId = 1L
        override suspend fun start() = Unit
        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) { responses.send(message) }
        override suspend fun close() { responses.close(); invokeOnCloseCallback() }
        suspend fun receive(message: JSONRPCMessage) { _onMessage(message) }
        suspend fun request(method: String, params: JsonObject = obj()): RequestResult = withTimeout(10_000) {
            val id = RequestId(nextId++)
            receive(JSONRPCRequest(id, method, params))
            var message: JSONRPCMessage
            do { message = responses.receive() } while (message !is JSONRPCResponse && message !is JSONRPCError)
            val response = assertIs<JSONRPCResponse>(message, message.toString())
            assertEquals(id, response.id)
            response.result
        }
        suspend fun call(name: String, arguments: JsonObject): CallToolResult = assertIs<CallToolResult>(request("tools/call", obj("name" to name, "arguments" to arguments)))
    }

    @Test fun `missing inputs invalid requirements and unsupported actions fail before writes`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val requirement = obj("name" to "Deliverable", "markdownContent" to "Body", "acceptanceCriteria" to JsonArray(listOf(JsonPrimitive(CRITERION))))
            val create = obj("action" to "create", "requestId" to uuid(900), "name" to "Plan", "markdownContent" to "Scope", "projectId" to PROJECT_ID, "requirements" to JsonArray(listOf(requirement)))
            val invalid = listOf(obj("action" to "unknown"), obj("action" to "context"), obj("action" to "update", "id" to SPEC_ID, "markdownContent" to ""), JsonObject(create - "projectId"), JsonObject(create + obj("name" to "")), JsonObject(create + obj("markdownContent" to "")), JsonObject(create + obj("requirements" to array()))) +
                listOf(requirement + obj("name" to ""), requirement + obj("markdownContent" to ""), requirement + obj("acceptanceCriteria" to array()), requirement + obj("acceptanceCriteria" to JsonArray(listOf(JsonPrimitive("")))), requirement + obj("acceptanceCriteria" to JsonArray(listOf(JsonPrimitive("one\ntwo")))), requirement + obj("acceptanceCriteria" to JsonArray(listOf(JsonPrimitive("one\rtwo")))), requirement + obj("acceptanceCriteria" to JsonArray(listOf(JsonPrimitive(" same"), JsonPrimitive("same ")))), requirement + obj("markdownContent" to "## Acceptance criteria"))
                    .map { JsonObject(create + obj("requirements" to JsonArray(listOf(JsonObject(it))))) }
            for (args in invalid) assertFailsWith<Exception> { fixture.handle(args) }
            assertTrue(fixture.requests.none { it.operation().startsWith("Create") || it.operation().startsWith("Add") || it.operation().startsWith("Set") })
            for (key in listOf("name", "markdownContent", "acceptanceCriteria")) assertFailsWith<kotlinx.serialization.SerializationException> {
                Json.decodeFromJsonElement(RequirementContentInput.serializer(), JsonObject(requirement - key))
            }
        }
    }
    @Test fun `project and spec keys resolve while missing lookup results are rejected`() = runBlocking<Unit> {
        Fixture(0).use { fixture ->
            assertEquals("initial-guide", fixture.handle(obj("action" to "instructions", "projectKey" to "WORK")).parseObject()["revision"]?.jsonPrimitive?.content)
            val created = fixture.handle(obj("action" to "create", "requestId" to uuid(901), "projectKey" to "WORK", "name" to "Plan", "markdownContent" to "Scope", "requirements" to JsonArray(listOf(obj("name" to "Behavior", "markdownContent" to "Body", "acceptanceCriteria" to JsonArray(listOf(JsonPrimitive(CRITERION)))))))).parseObject()
            assertEquals(SPEC_ID, created["specId"]?.jsonPrimitive?.content)
            assertEquals(SPEC_ID, fixture.handle(obj("action" to "context", "key" to "SPEC-1")).parseObject().getValue("spec").jsonObject.getValue("id").jsonPrimitive.content)
            fixture.transformResponse = { request, value -> when(request.operation()) {
                "GetWorkOpsProjectByKey" -> obj("workOps" to obj("projects" to obj("projectByKey" to null)))
                "GetWorkOpsSpecByKey" -> obj("workOps" to obj("specs" to obj("specByKey" to null)))
                else -> value
            } }
            assertFailsWith<IllegalStateException> { fixture.handle(obj("action" to "instructions", "projectKey" to "missing")) }
            assertFailsWith<IllegalStateException> { fixture.handle(obj("action" to "context", "key" to "missing")) }
        }
    }
    @Test fun `missing API objects reject edits and validation and fail verification closed`() = runBlocking<Unit> {
        val failures = listOf("GetWorkOpsPlanContext" to obj("workOps" to obj("specs" to obj("spec" to null))), "GetWorkOpsRequirement" to obj("workOps" to obj("requirements" to obj("requirement" to null))), "GetWorkOpsPlanTask" to obj("workOps" to obj("tasks" to obj("task" to null))), "GetMetadataDocumentContext" to obj("content" to obj("metadata" to null)))
        for ((operation, missing) in failures) Fixture().use { fixture ->
            fixture.transformResponse = { request, value -> if(request.operation() == operation) missing else value }
            val edit = obj("action" to "update_requirement", "id" to SPEC_ID, "requirementId" to fixture.requirements.single().id, "name" to "Changed", "markdownContent" to "Body", "acceptanceCriteria" to JsonArray(listOf(JsonPrimitive(CRITERION))))
            if (operation != "GetWorkOpsPlanContext") assertFailsWith<IllegalStateException> { fixture.handle(edit) }
            fixture.guidancePolicy = JsonObject(fixture.guidancePolicy + obj("requireInterfaceReview" to false, "requireTdd" to false, "requireTestQuality" to false))
            assertFailsWith<IllegalStateException> { fixture.handle(obj("action" to "record_validation", "id" to SPEC_ID, "requirementId" to fixture.requirements.single().id, "checks" to JsonArray(listOf(obj("criterion" to CRITERION, "outcome" to "PASSED", "evidence" to "Observed behavior"))))) }
            assertTrue(fixture.comments.isEmpty())
            if (operation == "GetWorkOpsPlanContext") {
                for (action in listOf("context", "next_action", "review", "update", "add_requirement")) {
                    val args = JsonObject(edit + obj("action" to action, "requestId" to uuid(902)))
                    assertFailsWith<IllegalStateException> { fixture.handle(args) }
                }
            }
        }
    }
    @Test fun `missing linked tasks reject both content replacement and evidence`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.transformResponse = { request, value -> if (request.operation() == "GetWorkOpsRequirement") obj("workOps" to obj("requirements" to obj("requirement" to JsonObject(value.getValue("workOps").jsonObject.getValue("requirements").jsonObject.getValue("requirement").jsonObject + obj("taskId" to null))))) else value }
            val edit = obj("action" to "update_requirement", "id" to SPEC_ID, "requirementId" to fixture.requirements.single().id, "name" to "Changed", "markdownContent" to "Body", "acceptanceCriteria" to JsonArray(listOf(JsonPrimitive(CRITERION))))
            assertFailsWith<IllegalStateException> { fixture.handle(edit) }
            assertFailsWith<IllegalStateException> { fixture.record() }
            assertTrue(fixture.requests.none { it.operation() == "SetWorkOpsRequirementContent" })
        }
    }
    @Test fun `blank unknown and missing criteria cannot produce passing validation`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.guidancePolicy = JsonObject(fixture.guidancePolicy + obj("requireInterfaceReview" to false, "requireTdd" to false, "requireTestQuality" to false))
            val base = obj("action" to "record_validation", "id" to SPEC_ID, "requirementId" to fixture.requirements.single().id)
            for (checks in listOf(array(), JsonArray(listOf(obj("criterion" to "", "outcome" to "PASSED", "evidence" to "Observed"))), JsonArray(listOf(obj("criterion" to CRITERION, "outcome" to "PASSED", "evidence" to ""))), JsonArray(listOf(obj("criterion" to "other", "outcome" to "PASSED", "evidence" to "Observed"))))) assertFailsWith<IllegalArgumentException> { fixture.handle(JsonObject(base + obj("checks" to checks))) }
            fixture.requirements.single().criteria = emptyList()
            assertFailsWith<IllegalArgumentException> { fixture.handle(JsonObject(base + obj("checks" to JsonArray(listOf(obj("criterion" to CRITERION, "outcome" to "PASSED", "evidence" to "Observed")))))) }
            assertTrue(fixture.comments.isEmpty())
        }
    }

    @Test fun `plan review rejects inconsistent pagination and concurrent scope changes`() = runBlocking<Unit> {
        for (scenario in listOf("count", "empty", "duplicate", "version", "disappeared")) Fixture(101).use { fixture ->
            var reads = 0
            fixture.transformResponse = { request, value ->
                if (request.operation() != "GetWorkOpsPlanContext") value else {
                    ++reads
                    val spec = value.getValue("workOps").jsonObject.getValue("specs").jsonObject.getValue("spec").jsonObject
                    val offset = request.variables()["offset"]?.jsonPrimitive?.long ?: 0
                    val changed = when {
                        scenario == "count" && offset > 0 -> JsonObject(spec + obj("requirementCount" to 102))
                        scenario == "empty" && offset > 0 -> JsonObject(spec + obj("requirements" to array()))
                        scenario == "duplicate" && offset > 0 -> JsonObject(spec + obj("requirements" to JsonArray(listOf(fixture.requirements.first().let { first -> spec.getValue("requirements").jsonArray.first().jsonObject + obj("id" to first.id) }.let(::JsonObject)))))
                        scenario == "version" && reads >= 4 -> JsonObject(spec + obj("version" to 2))
                        else -> spec
                    }
                    obj("workOps" to obj("specs" to obj("spec" to if (scenario == "disappeared" && reads >= 3) JsonNull else changed)))
                }
            }
            assertFailsWith<Exception> { fixture.handle(obj("action" to "review", "id" to SPEC_ID)) }
            assertTrue(fixture.comments.isEmpty())
        }
    }
    @Test fun `verification fails closed for inaccessible requirement pages and tasks`() = runBlocking<Unit> {
        Fixture(101).use { fixture ->
            fixture.transformResponse = { request, value ->
                if (request.operation() == "GetWorkOpsPlanContext" && request.variables().getValue("offset").jsonPrimitive.long > 0) {
                    val spec = value.getValue("workOps").jsonObject.getValue("specs").jsonObject.getValue("spec").jsonObject
                    obj("workOps" to obj("specs" to obj("spec" to JsonObject(spec + obj("requirements" to array())))))
                } else value
            }
            assertFalse(fixture.verify().ready())
            assertTrue(fixture.verify().issues().any { "Requirements changed" in it })
        }
        Fixture().use { fixture ->
            fixture.record()
            fixture.transformResponse = { request, value ->
                if (request.operation() == "GetWorkOpsPlanContext") {
                    val spec = value.getValue("workOps").jsonObject.getValue("specs").jsonObject.getValue("spec").jsonObject
                    val requirement = spec.getValue("requirements").jsonArray.single().jsonObject
                    obj("workOps" to obj("specs" to obj("spec" to JsonObject(spec + obj("requirements" to JsonArray(listOf(JsonObject(requirement + obj("task" to null)))))))))
                } else value
            }
            val verified = fixture.verify()
            assertFalse(verified.ready())
            assertTrue(verified.issues().any { "Linked task is missing" in it })
        }
    }
    @Test fun `edited evidence with blank observations wrong task ids and malformed framing cannot verify`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.record()
            val original = fixture.comments.last()
            val payload = original.getValue("content").jsonPrimitive.content.substringAfter("```json\n").substringBeforeLast("\n```").parseObject()
            val check = payload.getValue("checks").jsonArray.single().jsonObject
            for ((changed, expected) in listOf(JsonObject(payload + obj("taskId" to "other")) to "stale", JsonObject(payload + obj("checks" to JsonArray(listOf(JsonObject(check + obj("evidence" to "")))))) to "passing evidence", JsonObject(payload + obj("checks" to JsonArray(listOf(JsonObject(check + obj("tdd" to JsonObject(tddEvidence() + obj("green" to obj("command" to "", "outcome" to "FAILED", "evidence" to ""))))))))) to "TDD")) {
                fixture.comments[fixture.comments.lastIndex] = JsonObject(original + obj("content" to "## WorkOps validation\n\n```json\n$changed\n```"))
                assertTrue(fixture.verify().issues().any { expected in it })
            }
            for (value in listOf("## WorkOps validation\n\n```json\n{}\n```", "## WorkOps validation\n\n```json\n$payload", "## WorkOps validation\n\n```json\n" + JsonObject(payload + obj("schema" to "other")) + "\n```")) {
                fixture.comments[fixture.comments.lastIndex] = JsonObject(original + obj("content" to value))
                assertTrue(fixture.verify().issues().any { "Validation evidence is missing" in it })
            }
        }
    }
    @Test fun `review scope diffs include all references and structural-only document changes`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.handle(interfaceReviewArgs())
            fixture.transformResponse = { request, value -> when(request.operation()) {
                "GetWorkOpsPlanContext" -> {
                    val spec = value.getValue("workOps").jsonObject.getValue("specs").jsonObject.getValue("spec").jsonObject
                    obj("workOps" to obj("specs" to obj("spec" to JsonObject(spec + obj("projectId" to uuid(901), "programId" to uuid(902), "parentSpecId" to uuid(903), "gitRepositoryId" to uuid(904), "gitPath" to "specs/plan.md", "contexts" to JsonArray(listOf(obj("id" to uuid(400), "contextType" to "EXTERNAL_URI", "targetId" to "https://example.test/revised", "label" to null, "attributes" to obj("note" to "revised")))))))))
                }
                "GetMetadataDocumentContext" -> {
                    val metadata = value.getValue("content").jsonObject.getValue("metadata").jsonObject
                    val document = metadata.getValue("document").jsonObject
                    val body = document.getValue("content").jsonObject
                    val root = body.getValue("document").jsonObject
                    obj("content" to obj("metadata" to JsonObject(metadata + obj("document" to JsonObject(document + obj("content" to JsonObject(body + obj("document" to JsonObject(root + obj("attrs" to obj("type" to "empty", "class" to "reviewed")))))))))))
                }
                else -> value
            } }
            val diff = fixture.handle(obj("action" to "diff", "id" to SPEC_ID)).parseObject().getValue("changes").jsonArray
            assertTrue(diff.any { it.jsonObject["textUnchanged"]?.jsonPrimitive?.booleanOrNull == true })
            assertTrue(diff.count { it.jsonObject["field"]?.jsonPrimitive?.content == "scope" } >= 4 || diff.any { it.toString().contains("gitRepository") })
            assertEquals("REVIEW", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()["action"]?.jsonPrimitive?.content)
        }
    }


    @Test fun `pagination arguments reject invalid ranges and preserve valid boundary values`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            for (key in listOf("offset", "commentOffset", "generationOffset")) for (value in listOf(-1L, Int.MAX_VALUE.toLong() + 1)) {
                assertFailsWith<IllegalArgumentException> { fixture.handle(obj("action" to "context", "id" to SPEC_ID, key to value)) }
            }
            for (value in listOf(0, 101)) assertFailsWith<IllegalArgumentException> { fixture.handle(obj("action" to "context", "id" to SPEC_ID, "limit" to value)) }
            assertEquals(0, fixture.handle(obj("action" to "context", "id" to SPEC_ID, "offset" to Int.MAX_VALUE, "commentOffset" to 0, "generationOffset" to 0, "limit" to 100)).parseObject().getValue("returnedRequirements").jsonPrimitive.int)
            assertFailsWith<IllegalArgumentException> { fixture.handle(obj("action" to "diff", "id" to SPEC_ID, "reviewId" to 0)) }
            assertFailsWith<Exception> { fixture.handle(obj("action" to JsonNull)) }
        }
    }

    @Test fun `inaccessible documents preserve title fallback and reject edits without metadata`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.handle(obj("action" to "update", "id" to SPEC_ID, "name" to "New title", "markdownContent" to "New body"))
            assertEquals("New title", fixture.specTitle)
            fixture.missingDocument = SPEC_METADATA_ID
            fixture.handle(obj("action" to "update", "id" to SPEC_ID, "markdownContent" to "Fallback body"))
            assertEquals("Plan", fixture.specTitle)
            fixture.transformResponse = { request, value -> if (request.operation() == "GetMetadataDocumentContext") obj("content" to obj("metadata" to null)) else value }
            assertFailsWith<IllegalStateException> { fixture.handle(obj("action" to "update", "id" to SPEC_ID, "markdownContent" to "Body")) }
            assertFalse(fixture.verify().ready())
            assertFailsWith<IllegalStateException> { WorkOpsPlanToolRegistrar.writeRequirementContent(fixture.api, fixture.content, kotlin.uuid.Uuid.parse(fixture.requirements.single().id), RequirementContentInput("Requirement", "Body", listOf(CRITERION))) }
            fixture.transformResponse = { request, value -> if (request.operation() == "GetWorkOpsRequirement") obj("workOps" to obj("requirements" to obj("requirement" to null))) else value }
            assertFailsWith<IllegalStateException> { WorkOpsPlanToolRegistrar.writeRequirementContent(fixture.api, fixture.content, kotlin.uuid.Uuid.parse(fixture.requirements.single().id), RequirementContentInput("Requirement", "Body", listOf(CRITERION))) }
        }
    }

    @Test fun `inaccessible requirement content and spec bodies invalidate evidence and cannot be recorded`() = runBlocking<Unit> {
        for (missing in listOf(SPEC_METADATA_ID, uuid(200))) Fixture().use { fixture ->
            fixture.record(); fixture.missingDocument = missing
            val verification = fixture.verify()
            assertFalse(verification.ready()); assertTrue(verification.issues().any { "missing" in it || "inaccessible" in it })
            if (missing == SPEC_METADATA_ID) assertFailsWith<IllegalStateException> { fixture.record() }
            else assertFailsWith<IllegalArgumentException> { fixture.record() }
        }
        Fixture(0).use { fixture ->
            fixture.specCategory = "CANCELLED"
            val verification = fixture.verify()
            assertTrue(verification.issues().any { "no requirements" in it })
            assertTrue(verification.issues().any { "cancelled" in it })
        }
    }

    @Test fun `review comments reject incomplete declarations foreign schemas and framing`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.handle(JsonObject(interfaceReviewArgs() + obj("reviewNote" to ""))) }
            assertFailsWith<IllegalArgumentException> { WorkOpsPlanReview.review(fixture.api, fixture.content, kotlin.uuid.Uuid.parse(SPEC_ID), null, listOf(ReviewedInterface("Contract.kt", "interface Contract"))) }
            fixture.handle(interfaceReviewArgs())
            val original = fixture.comments.single()
            val payload = original.getValue("content").jsonPrimitive.content.substringAfter("```json\n").substringBeforeLast("\n```").parseObject()
            for (bad in listOf(JsonObject(payload + obj("schema" to "wrong")), JsonObject(payload + obj("specId" to uuid(999))), JsonObject(payload + obj("note" to null)), JsonObject(payload + obj("note" to "")), JsonObject(payload + obj("interfaces" to JsonArray(listOf(obj("path" to "", "declaration" to "Contract"))))), JsonObject(payload + obj("interfaces" to JsonArray(listOf(obj("path" to "Contract.kt", "declaration" to ""))))))) {
                fixture.comments[0] = JsonObject(original + obj("content" to "## WorkOps review\n\n```json\n$bad\n```"))
                assertFalse(fixture.handle(obj("action" to "diff", "id" to SPEC_ID)).parseObject().getValue("hasReview").jsonPrimitive.boolean)
                assertEquals("REVIEW", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()["action"]?.jsonPrimitive?.content)
            }
            fixture.comments[0] = JsonObject(original + obj("content" to "## WorkOps review\n\n```json\n$payload"))
            assertFalse(fixture.handle(obj("action" to "diff", "id" to SPEC_ID)).parseObject().getValue("hasReview").jsonPrimitive.boolean)
            fixture.comments[0] = original
            assertTrue(fixture.handle(obj("action" to "diff", "id" to SPEC_ID, "reviewId" to 1)).parseObject().getValue("hasReview").jsonPrimitive.boolean)
            fixture.specTitle = "Revised title"; fixture.requirements.single().title = "Revised requirement"
            val diff = fixture.handle(obj("action" to "diff", "id" to SPEC_ID)).parseObject().getValue("changes").jsonArray
            assertEquals(2, diff.count { it.jsonObject["field"] == JsonPrimitive("title") })
        }
    }

    @Test fun `generated task access and dependency direction remain visible to recommendations`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.handle(interfaceReviewArgs())
            fixture.generations += obj("id" to uuid(950), "metadataVersion" to 1, "source" to "MANUAL", "generatedTaskIds" to array(GENERATED_TASK_ID), "diffSummary" to null, "created" to "2026-10-09T12:00:00Z")
            assertEquals("UNBLOCK", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()["action"]?.jsonPrimitive?.content)
            assertFalse(fixture.verify().ready())
            fixture.generatedTask = JsonObject(fixture.taskJson(fixture.requirements.single(), "TODO") + obj("id" to GENERATED_TASK_ID, "resolution" to null))
            val next = fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()
            assertEquals("START_TASK", next["action"]?.jsonPrimitive?.content); assertEquals(JsonNull, next["requirementId"])
            val tasks = fixture.verify().getValue("generatedTasks").jsonArray
            assertEquals(JsonNull, tasks.single().jsonObject.getValue("task").jsonObject["resolution"])
            fixture.generatedTask = JsonObject(requireNotNull(fixture.generatedTask) + obj("status" to status("CANCELLED")))
            assertEquals("VERIFY", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()["action"]?.jsonPrimitive?.content)
        }
        Fixture().use { fixture ->
            fixture.handle(interfaceReviewArgs()); val requirement = fixture.requirements.single(); requirement.category = "TODO"
            val outgoing = blockingLink(requirement, requirement)
            for (target in listOf(JsonNull, obj("id" to uuid(999), "key" to "TASK-X", "summary" to "Other", "status" to status("TODO")))) {
                fixture.taskLinks[requirement.taskId] = listOf(JsonObject(outgoing + obj("sourceTask" to null, "targetTask" to target)))
                val next = fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()
                assertEquals("UNBLOCK", next["action"]?.jsonPrimitive?.content)
                assertTrue(next.getValue("blockedTasks").jsonArray.single().jsonObject.getValue("blockers").jsonArray.single().jsonObject.getValue("reason").jsonPrimitive.content.contains("direction"))
            }
        }
    }

    @Test fun `cancelled generated tasks satisfy the same completion rule as backend transitions`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.record()
            fixture.generations += obj("id" to uuid(951), "metadataVersion" to 1, "source" to "MANUAL", "generatedTaskIds" to array(GENERATED_TASK_ID), "diffSummary" to null, "created" to "2026-10-09T12:00:00Z")
            fixture.generatedTask = JsonObject(fixture.taskJson(fixture.requirements.single(), "CANCELLED") + obj("id" to GENERATED_TASK_ID))
            assertTrue(fixture.verify().ready())
        }
    }

    @Test fun `blocked evidence and unknown checks cannot make CLI verification disagree with completion`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.record()
            val original = fixture.comments.last()
            fixture.comments[fixture.comments.lastIndex] = JsonObject(original + obj("status" to "BLOCKED"))
            assertFalse(fixture.verify().ready())
            fixture.comments[fixture.comments.lastIndex] = original
            val body = original.getValue("content").jsonPrimitive.content.substringAfter("```json\n").substringBeforeLast("\n```").parseObject()
            val check = body.getValue("checks").jsonArray.single().jsonObject
            val unknown = JsonObject(check + obj("criterion" to "Not in plan"))
            val withUnknown = JsonObject(body + obj("checks" to JsonArray(listOf(check, unknown))))
            fixture.comments[fixture.comments.lastIndex] = JsonObject(original + obj("content" to "## WorkOps validation\n\n```json\n$withUnknown\n```"))
            assertFalse(fixture.verify().ready())
            fixture.comments[fixture.comments.lastIndex] = original
            fixture.comments[0] = JsonObject(fixture.comments[0] + obj("status" to "BLOCKED"))
            assertFalse(fixture.verify().ready())
        }
    }

    @Test fun `nullable scopes strict ownership and missing lookup responses remain explicit`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.handle(interfaceReviewArgs())
            fixture.transformResponse = { request, value -> when (request.operation()) {
                "GetWorkOpsProjectByKey" -> obj("workOps" to obj("projects" to obj("projectByKey" to null)))
                "GetWorkOpsPlanContext" -> value.mapObject("workOps", "specs", "spec") { JsonObject(it + obj("projectId" to null)) }
                "GetWorkOpsRequirement" -> value.mapObject("workOps", "requirements", "requirement") { JsonObject(it + obj("parentType" to "PROGRAM")) }
                else -> value
            } }
            assertTrue(fixture.handle(obj("action" to "diff", "id" to SPEC_ID)).parseObject().getValue("changes").jsonArray.any { it.jsonObject["key"] == JsonPrimitive("project") })
            assertFailsWith<IllegalStateException> { fixture.handle(JsonObject(createArgs() - "projectId" + obj("projectKey" to "missing"))) }
            val edit = obj("action" to "update_requirement", "id" to SPEC_ID, "requirementId" to fixture.requirements.single().id, "name" to "Name", "markdownContent" to "Body", "acceptanceCriteria" to array(CRITERION))
            assertFailsWith<IllegalArgumentException> { fixture.handle(edit) }
            fixture.guidancePolicy = JsonObject(fixture.guidancePolicy + obj("requireInterfaceReview" to false))
            assertFailsWith<IllegalArgumentException> { fixture.record() }
        }
    }

    @Test fun `snapshot failures at each read and oversized pages fail without writing review`() = runBlocking<Unit> {
        for (missingRead in listOf(2, 3)) Fixture().use { fixture ->
            var reads = 0
            fixture.transformResponse = { request, value -> if (request.operation() == "GetWorkOpsPlanContext" && ++reads == missingRead) obj("workOps" to obj("specs" to obj("spec" to null))) else value }
            assertFailsWith<IllegalStateException> { fixture.handle(obj("action" to "review", "id" to SPEC_ID)) }
            assertTrue(fixture.comments.isEmpty())
        }
        Fixture(2).use { fixture ->
            fixture.transformResponse = { request, value -> if (request.operation() == "GetWorkOpsPlanContext") value.mapObject("workOps", "specs", "spec") { JsonObject(it + obj("requirementCount" to 1)) } else value }
            assertFailsWith<IllegalStateException> { fixture.handle(obj("action" to "review", "id" to SPEC_ID)) }
        }
        Fixture(101).use { fixture ->
            fixture.transformResponse = { request, value -> if (request.operation() == "GetWorkOpsPlanContext" && request.variables().getValue("offset").jsonPrimitive.long > 0) obj("workOps" to obj("specs" to obj("spec" to null))) else value }
            assertFailsWith<IllegalStateException> { fixture.verify() }
        }
    }

    @Test fun `recommendations follow disabled interface policy task transitions and all generation pages`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.guidancePolicy = JsonObject(fixture.guidancePolicy + obj("requireInterfaceReview" to false))
            fixture.handle(obj("action" to "review", "id" to SPEC_ID))
            repeat(101) { fixture.generations += obj("id" to uuid(1000 + it), "metadataVersion" to 1, "source" to "MANUAL", "generatedTaskIds" to array(), "diffSummary" to null, "created" to "2026-10-09T12:00:00Z") }
            assertEquals("VERIFY", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()["action"]?.jsonPrimitive?.content)
            val requirement = fixture.requirements.single(); requirement.category = "TODO"
            val unavailable = JsonObject(transition() + obj("toState" to obj("id" to uuid(802), "status" to status("TODO"))))
            fixture.transformResponse = { request, value ->
                if (request.operation() == "GetWorkOpsPlanTask") value.mapObject("workOps", "tasks", "task") { task ->
                    JsonObject(task + obj("transitions" to JsonArray(listOf(unavailable, transition()))))
                } else value
            }
            assertEquals("START_TASK", fixture.handle(obj("action" to "next_action", "id" to SPEC_ID)).parseObject()["action"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `edited counters missing review and independent linked task status cannot establish readiness`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.record()
            val original = fixture.comments.last()
            val payload = original.getValue("content").jsonPrimitive.content.substringAfter("```json\n").substringBeforeLast("\n```").parseObject()
            val quality = payload.getValue("testQuality").jsonObject
            val coverage = quality.getValue("coverage").jsonArray.single().jsonObject
            val emptyLines = JsonObject(payload + obj("testQuality" to JsonObject(quality + obj("coverage" to JsonArray(listOf(JsonObject(coverage + obj("lineCovered" to 0, "lineMissed" to 0))))))))
            fixture.comments[fixture.comments.lastIndex] = JsonObject(original + obj("content" to "## WorkOps validation\n\n```json\n$emptyLines\n```"))
            val verification = fixture.verify()
            assertFalse(verification.ready())
            assertEquals(JsonNull, verification.getValue("requirements").jsonArray.single().jsonObject.getValue("coverageSummary").jsonArray.single().jsonObject["linePercent"])
            fixture.comments[fixture.comments.lastIndex] = original
            fixture.comments[0] = JsonObject(fixture.comments[0] + obj("deleted" to true))
            assertTrue(fixture.verify().issues().any { "current interface review" in it })
            fixture.comments[0] = JsonObject(fixture.comments[0] + obj("deleted" to false))
            fixture.transformResponse = { request, value -> if (request.operation() == "GetWorkOpsPlanContext") value.mapObject("workOps", "specs", "spec") { spec ->
                val requirement = spec.getValue("requirements").jsonArray.single().jsonObject
                JsonObject(spec + obj("requirements" to JsonArray(listOf(JsonObject(requirement + obj("task" to JsonObject(requirement.getValue("task").jsonObject + obj("status" to status("TODO")))))))))
            } else value }
            assertTrue(fixture.verify().issues().any { "must both be done" in it })
            fixture.transformResponse = { request, value -> if (request.operation() == "GetMetadataDocumentContext" && request.variables().getValue("id") == JsonPrimitive(uuid(200))) obj("content" to obj("metadata" to null)) else value }
            assertFalse(fixture.verify().ready())
            fixture.guidancePolicy = JsonObject(fixture.guidancePolicy + obj("requireInterfaceReview" to false))
            assertFailsWith<IllegalStateException> { fixture.record() }
        }
    }

    @Test fun `rich acceptance criteria exclude other sections but include ordered lists and nested text`() {
        fun paragraph(text: String) = obj("type" to "paragraph", "content" to JsonArray(listOf(obj("type" to "text", "text" to text))))
        fun heading(level: Int, text: String) = obj("type" to "heading", "attrs" to obj("level" to level), "content" to JsonArray(listOf(obj("type" to "text", "text" to text))))
        val blocks = JsonArray(listOf(heading(1, "Acceptance criteria"), heading(2, "Other"), paragraph("Before"), heading(2, "Acceptance criteria"), paragraph("Context"), heading(3, "Examples"),
            obj("type" to "orderedList", "attrs" to obj("start" to 1), "content" to JsonArray(listOf(obj("type" to "listItem", "content" to JsonArray(listOf(paragraph(CRITERION)))), obj("type" to "listItem", "content" to JsonArray(listOf(paragraph(" ")))), obj("type" to "listItem", "content" to JsonArray(listOf(paragraph(CRITERION))))))),
            heading(2, "Next"), obj("type" to "bulletList", "content" to JsonArray(listOf(obj("type" to "listItem", "content" to JsonArray(listOf(paragraph("Excluded")))))))))
        assertEquals(listOf(CRITERION), WorkOpsPlanToolRegistrar.acceptanceCriteria(obj("document" to obj("type" to "doc", "content" to blocks))))
    }

    @Test fun `API defaults missing generation specs and direct task edits preserve observed contracts`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            assertEquals("initial-guide", fixture.api.getEffectiveGuidance().revision)
            assertTrue(fixture.api.getPlanGenerations(kotlin.uuid.Uuid.parse(SPEC_ID)).isEmpty())
            fixture.record()
            val requirement = fixture.requirements.single()
            val document = requirement.body
            val updated = fixture.api.updateTask(kotlin.uuid.Uuid.parse(requirement.taskId), bosca.graphql.gen.UpdateWorkOpsTaskInput(summary = "Task revision", descriptionMarkdown = "Independent task draft", expectedVersion = requirement.taskVersion))
            assertEquals("Task revision", updated.summary); assertEquals("Independent task draft", updated.descriptionMarkdown)
            assertEquals(document, requirement.body)
            assertTrue(fixture.verify().issues().any { "stale" in it })
            fixture.transformResponse = { request, value -> if (request.operation() == "GetWorkOpsPlanGenerations") obj("workOps" to obj("specs" to obj("spec" to null))) else value }
            assertFailsWith<IllegalStateException> { fixture.api.getPlanGenerations(kotlin.uuid.Uuid.parse(SPEC_ID)) }
        }
    }

    @Test fun `MCP instruction aliases and requirement keys exercise live guidance and controlled failures`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val server = Server(Implementation("test", "1"), ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))), instructions = MCP_SERVER_INSTRUCTIONS)
            McpToolRegistrar.registerAll(server, fixture.api, fixture.content)
            val transport = LoopbackTransport(); server.createSession(transport)
            try {
                val initialized = assertIs<InitializeResult>(transport.request("initialize", obj("protocolVersion" to "2025-06-18", "capabilities" to obj(), "clientInfo" to obj("name" to "test", "version" to "1"))))
                assertTrue(requireNotNull(initialized.instructions).contains("workops_spec_instructions"))
                assertTrue(requireNotNull(initialized.instructions).contains("Never invent evidence"))
                transport.receive(JSONRPCNotification("notifications/initialized"))
                val noArgs = assertIs<CallToolResult>(transport.request("tools/call", obj("name" to "workops_spec_instructions")))
                assertEquals("initial-guide", noArgs.output()["revision"]?.jsonPrimitive?.content)
                for (args in listOf(obj(), obj("projectId" to PROJECT_ID), obj("projectKey" to "WORK"))) {
                    assertEquals("initial-guide", transport.call("workops_spec_instructions", args).output()["revision"]?.jsonPrimitive?.content)
                }
                fixture.guidanceRevision = "updated-guide"
                assertEquals("updated-guide", transport.call("workops_spec_instructions", obj()).output()["revision"]?.jsonPrimitive?.content)
                val edit = obj("action" to "set_content", "key" to "REQ-1", "name" to "Revised", "markdownContent" to "Body", "acceptanceCriteria" to array(CRITERION))
                assertFalse(transport.call("workops_requirement", edit).isError == true)
                assertEquals("Revised", fixture.requirements.single().summary)
                assertTrue(fixture.requirements.single().description.contains(CRITERION))
                assertTrue(transport.call("workops_requirement", JsonObject(edit - "key")).isError == true)
                assertTrue(transport.call("workops_requirement", JsonObject(edit + obj("key" to "missing"))).isError == true)
                assertTrue(transport.call("workops_requirement", obj("action" to "unknown")).isError == true)
                fixture.transformResponse = { request, value -> when (request.operation()) {
                    "GetWorkOpsSpecTransitions" -> obj("workOps" to obj("specs" to obj("spec" to null)))
                    "GetWorkOpsProjectByKey" -> obj("workOps" to obj("projects" to obj("projectByKey" to null)))
                    else -> value
                } }
                assertTrue(transport.call("workops_spec", obj("action" to "transitions", "id" to SPEC_ID)).isError == true)
                assertTrue(transport.call("workops_spec_instructions", obj("projectKey" to "missing")).isError == true)
                fixture.failOperation = "GetEffectiveWorkOpsGuidance"
                assertTrue(transport.call("workops_spec_instructions", obj()).isError == true)
            } finally { server.close() }
        }
    }

    private class RequirementState(val index: Int) {
        val id = uuid(100 + index)
        val metadataId = uuid(200 + index)
        val taskId = uuid(300 + index)
        var body = "Implement behavior"
        var criteria = listOf(CRITERION)
        var summary = "Requirement $index"
        var title = summary
        var description = "Implement behavior"
        var taskVersion = 1L
        var category = "DONE"
        var allowStart = true
    }

    private class Fixture(initialRequirements: Int = 1) : AutoCloseable {
        val requirements = (0 until initialRequirements).map(::RequirementState).toMutableList()
        val comments = mutableListOf<JsonObject>()
        val generations = mutableListOf<JsonObject>()
        val requests = CopyOnWriteArrayList<JsonObject>()
        val authorizations = CopyOnWriteArrayList<String>()
        var guidanceRevision = "initial-guide"
        var guidancePolicy = obj("requireInterfaceReview" to true, "requireTdd" to true, "requireTestQuality" to true,
            "minimumLineCoverage" to 99.0, "minimumBranchCoverage" to 97.0)
        var specBody = "Current plan"
        var specTitle = "Plan"
        var failOperation: String? = null
        var missingDocument: String? = null
        var requirementParentId = SPEC_ID
        var childCount = 0
        var specCategory = "IN_PROGRESS"
        val taskLinks = mutableMapOf<String, List<JsonObject>>()
        var transformResponse: (JsonObject, JsonObject) -> JsonObject = { _, value -> value }
        private val creationReceipts = mutableMapOf<String, Pair<JsonObject, JsonObject>>()
        var generatedTask: JsonObject? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val network: NetworkClient
        val api: WorkOpsApi
        val content: ContentApi

        init {
            server.createContext("/graphql") { exchange ->
                try {
                    val request = exchange.requestBody.bufferedReader().use { it.readText() }.parseObject()
                    requests.add(request)
                    authorizations.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
                    val payload = if (request.operation() == failOperation) obj("errors" to JsonArray(listOf(obj("message" to "simulated optimistic-lock conflict"))))
                        else obj("data" to transformResponse(request, respond(request)))
                    val bytes = payload.toString().toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (e: Exception) {
                    val bytes = obj("errors" to JsonArray(listOf(obj("message" to "fixture error: ${e.message}")))).toString().toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
            network = NetworkClient("http://127.0.0.1:${server.address.port}/graphql").apply { tokenProvider = { "test-token" } }
            api = WorkOpsApi(network); content = ContentApi(network)
        }

        suspend fun handle(args: JsonObject): String = WorkOpsPlanToolRegistrar.handle(api, content, args)
        suspend fun verify(limit: Int = 50) = handle(obj("action" to "verify", "id" to SPEC_ID, "limit" to limit)).parseObject()
        suspend fun record(requirementId: String = requirements.first().id, outcome: String = "PASSED"): String {
            if (comments.none { it.getValue("content").jsonPrimitive.content.contains("\"interfaces\":[{") }) handle(interfaceReviewArgs())
            return handle(obj(
                "action" to "record_validation", "id" to SPEC_ID, "requirementId" to requirementId, "testQuality" to qualityEvidence(),
                "checks" to JsonArray(listOf(obj("criterion" to CRITERION, "outcome" to outcome,
                    "evidence" to "./gradlew :cli:test — assertions passed", "tdd" to tddEvidence()))),
            ))
        }

        fun addComment(text: String): JsonObject = obj(
            "id" to comments.size + 1L, "parentId" to null, "specId" to SPEC_ID, "profileId" to PRINCIPAL_ID,
            "visibility" to "USER", "created" to "2026-10-09T12:00:00Z", "modified" to "2026-10-09T12:00:00Z",
            "status" to "APPROVED", "content" to text, "likes" to 0, "deleted" to false,
        ).also { comments.add(it) }

        private fun respond(request: JsonObject): JsonObject {
            val variables = request.variables()
            fun requirement() = requirements.first { it.id == variables.getValue("id").jsonPrimitive.content }
            fun page(values: List<JsonObject>) = JsonArray(values.drop(variables["offset"]?.jsonPrimitive?.int ?: 0).take(variables["limit"]?.jsonPrimitive?.int ?: 50))
            return when (request.operation()) {
                "GetWorkOpsProjectByKey" -> obj("workOps" to obj("projects" to obj("projectByKey" to obj("id" to PROJECT_ID, "key" to "WORK", "name" to "Work", "description" to null, "ownerProfileId" to PRINCIPAL_ID, "defaultTaskTypeSchemeId" to null, "defaultWorkflowSchemeId" to null, "taskCreationFormSchemaKey" to "workops-task-create", "archivedAt" to null, "createdAt" to "2026-10-09T12:00:00Z", "modifiedAt" to "2026-10-09T12:00:00Z", "version" to 1))))
                "GetEffectiveWorkOpsGuidance" -> obj("workOps" to obj("guidance" to obj("effective" to obj(
                    "revision" to guidanceRevision, "policy" to guidancePolicy, "policyInherited" to false,
                    "guides" to JsonArray(listOf(obj("phase" to "IMPLEMENTATION", "name" to "custom-implementation",
                        "description" to "Use when implementing", "markdown" to "# Custom workflow", "inherited" to false)))))))
                "CreateWorkOpsSpecPlan" -> {
                    val input = variables.getValue("input").jsonObject
                    val key = input.getValue("requestId").jsonPrimitive.content
                    creationReceipts[key]?.let { check(it.first == input); return it.second }
                    specBody = input.getValue("markdownContent").jsonPrimitive.content
                    specTitle = input.getValue("name").jsonPrimitive.content
                    for (entry in input.getValue("requirements").jsonArray) {
                        val state = RequirementState(requirements.size).also { requirements.add(it) }
                        replaceContent(state, entry.jsonObject)
                    }
                    val result = obj("workOps" to obj("specs" to obj("createPlan" to obj("id" to SPEC_ID, "key" to "SPEC-1", "metadataId" to SPEC_METADATA_ID))))
                    creationReceipts[key] = input to result
                    result
                }
                "AddWorkOpsPlanRequirement" -> {
                    val key = variables.getValue("requestId").jsonPrimitive.content
                    creationReceipts[key]?.let { check(it.first == variables); return it.second }
                    val state = RequirementState(requirements.size).also { requirements.add(it) }
                    replaceContent(state, variables.getValue("input").jsonObject)
                    val result = obj("workOps" to obj("specs" to obj("addPlanRequirement" to obj("id" to state.id, "key" to "REQ-${state.index + 1}", "metadataId" to state.metadataId, "taskId" to state.taskId))))
                    creationReceipts[key] = variables to result
                    result
                }
                "SetWorkOpsRequirementContent" -> {
                    val state = requirement()
                    val input = variables.getValue("input").jsonObject
                    check(input.getValue("expectedTaskVersion").jsonPrimitive.long == state.taskVersion)
                    replaceContent(state, input.getValue("content").jsonObject)
                    obj("workOps" to obj("requirements" to obj("setContent" to obj("id" to state.id, "key" to "REQ-${state.index + 1}", "metadataId" to state.metadataId, "taskId" to state.taskId))))
                }
                "GetWorkOpsPlanTaskLinks" -> obj("workOps" to obj("links" to obj("forTask" to JsonArray(taskLinks[variables.getValue("taskId").jsonPrimitive.content].orEmpty()))))
                "CreateWorkOpsSpec" -> obj("workOps" to obj("specs" to obj("create" to specFragment())))
                "GetWorkOpsSpecByKey" -> obj("workOps" to obj("specs" to obj("specByKey" to specFragment())))
                "CreateWorkOpsRequirement" -> {
                    val state = RequirementState(requirements.size).also { requirements.add(it) }
                    obj("workOps" to obj("requirements" to obj("create" to requirementJson(state))))
                }
                "GetWorkOpsRequirementByKey" -> obj("workOps" to obj("requirements" to obj("requirementByKey" to requirements.firstOrNull { "REQ-${it.index + 1}" == variables.getValue("key").jsonPrimitive.content }?.let { requirementJson(it) })))
                "GetWorkOpsRequirement" -> obj("workOps" to obj("requirements" to obj("requirement" to requirementJson(requirement()))))
                "GetMetadataDocumentContext" -> {
                    val id = variables.getValue("id").jsonPrimitive.content
                    val state = requirements.firstOrNull { it.metadataId == id }
                    val document = if (id == missingDocument) null else obj("title" to (state?.title ?: specTitle), "content" to documentTree(state?.body ?: specBody, state?.criteria.orEmpty()))
                    obj("content" to obj("metadata" to obj("id" to id, "version" to 3, "name" to (state?.summary ?: "Plan"), "deleted" to false, "document" to document)))
                }
                "SetMetadataMarkdown" -> {
                    val body = variables.getValue("markdown").jsonPrimitive.content
                    val state = requirements.firstOrNull { it.metadataId == variables.getValue("id").jsonPrimitive.content }
                    if (state == null) {
                        specBody = body
                        specTitle = variables.getValue("title").jsonPrimitive.content
                    } else {
                        state.body = body
                        state.title = variables.getValue("title").jsonPrimitive.content
                        state.criteria = body.substringAfter("## Acceptance criteria\n\n", "").lines()
                            .filter { it.startsWith("- ") }.map { it.removePrefix("- ") }
                    }
                    obj("content" to obj("metadata" to obj("setMetadataMarkdown" to true)))
                }
                "GetWorkOpsPlanTask" -> {
                    val id = variables.getValue("taskId").jsonPrimitive.content
                    val task = if (id == GENERATED_TASK_ID) generatedTask else taskJson(requirements.first { it.taskId == id })
                    obj("workOps" to obj("tasks" to obj("task" to task)))
                }
                "UpdateWorkOpsTask" -> {
                    val state = requirements.first { it.taskId == variables.getValue("id").jsonPrimitive.content }
                    val input = variables.getValue("input").jsonObject
                    check(input.getValue("expectedVersion").jsonPrimitive.long == state.taskVersion)
                    state.summary = input.getValue("summary").jsonPrimitive.content
                    state.description = input.getValue("descriptionMarkdown").jsonPrimitive.content
                    state.taskVersion++
                    val task = JsonObject(taskJson(state) + obj(
                        "reporterProfileId" to PRINCIPAL_ID, "assigneeProfileId" to null, "sprintId" to null, "milestoneId" to null,
                        "labelIds" to array(), "componentIds" to array(), "affectsVersionIds" to array(), "fixVersionIds" to array(),
                        "originalEstimateSeconds" to null, "remainingEstimateSeconds" to null, "timeSpentSeconds" to 0,
                        "dueDate" to null, "startDate" to null, "resolutionAt" to null, "slaDueAt" to null,
                        "contentItemId" to null, "collectionId" to null, "epicTotalEstimateSeconds" to 0,
                        "epicTotalRemainingSeconds" to 0, "epicTotalSpentSeconds" to 0, "epicChildCount" to 0, "epicChildDoneCount" to 0,
                        "watcherProfileIds" to array(), "voteCount" to 0, "deletedAt" to null,
                        "createdAt" to "2026-10-09T12:00:00Z", "modifiedAt" to "2026-10-09T12:00:00Z",
                        "createdByPrincipalId" to PRINCIPAL_ID, "modifiedByPrincipalId" to PRINCIPAL_ID, "customFields" to obj(),
                        "project" to obj("id" to PROJECT_ID, "key" to "WORK", "name" to "Work"), "affectedProjects" to array(),
                        "taskType" to obj("id" to uuid(700), "name" to "Task", "hierarchyLevel" to "STANDARD"),
                        "priority" to obj("id" to uuid(500), "name" to "Normal", "displayOrder" to 0), "parentTask" to null, "epicTask" to null,
                    ))
                    obj("workOps" to obj("tasks" to obj("update" to task)))
                }
                "GetWorkOpsPlanContext" -> obj("workOps" to obj("specs" to obj("spec" to obj(
                    "id" to SPEC_ID, "key" to "SPEC-1", "metadataId" to SPEC_METADATA_ID, "version" to 1, "projectId" to PROJECT_ID, "programId" to null, "parentSpecId" to null, "gitRepositoryId" to null, "gitPath" to null,
                    "status" to status(specCategory), "childCount" to childCount, "childDoneCount" to 0,
                    "requirementCount" to requirements.size, "transitions" to JsonArray(listOf(transition())),
                    "requirements" to page(requirements.map { state -> obj("id" to state.id, "key" to "REQ-${state.index + 1}",
                        "metadataId" to state.metadataId, "version" to 1, "taskId" to state.taskId, "status" to status(state.category), "task" to taskJson(state)) }),
                    "contexts" to JsonArray(listOf(obj("id" to uuid(400), "contextType" to "EXTERNAL_URI", "targetId" to "https://example.test/design", "label" to "Design", "attributes" to null))),
                ))))
                "GetWorkOpsSpecTransitions" -> obj("workOps" to obj("specs" to obj("spec" to obj("id" to SPEC_ID, "key" to "SPEC-1", "version" to 1, "transitions" to JsonArray(listOf(transition()))))))
                "GetWorkOpsSpecComments" -> obj("workOps" to obj("specComments" to obj("forSpec" to page(comments))))
                "AddWorkOpsSpecComment" -> {
                    val comment = addComment(variables.getValue("input").jsonObject.getValue("content").jsonPrimitive.content)
                    obj("workOps" to obj("specComments" to obj("add" to comment)))
                }
                "GetWorkOpsPlanGenerations" -> obj("workOps" to obj("specs" to obj("spec" to obj("taskGenerations" to page(generations)))))
                else -> error("Unexpected operation ${request.operation()}")
            }
        }

        private fun replaceContent(state: RequirementState, input: JsonObject) {
            state.summary = input.getValue("name").jsonPrimitive.content
            state.title = state.summary
            state.criteria = input.getValue("acceptanceCriteria").jsonArray.map { it.jsonPrimitive.content.trim() }
            state.body = input.getValue("markdownContent").jsonPrimitive.content.trimEnd() + "\n\n## Acceptance criteria\n\n" + state.criteria.joinToString("\n") { "- $it" } + "\n"
            state.description = state.body
            state.taskVersion++
        }

        private fun requirementJson(state: RequirementState) = obj(
            "id" to state.id, "key" to "REQ-${state.index + 1}", "metadataId" to state.metadataId,
            "parentType" to "SPEC", "parentId" to requirementParentId, "sortOrder" to 0, "labelIds" to array(),
            "deletedAt" to null, "createdAt" to "2026-10-09T12:00:00Z", "modifiedAt" to "2026-10-09T12:00:00Z",
            "createdByPrincipalId" to PRINCIPAL_ID, "modifiedByPrincipalId" to PRINCIPAL_ID, "version" to 1,
            "status" to status(state.category), "priority" to obj("id" to uuid(500), "name" to "Normal", "displayOrder" to 0),
            "assigneeProfileId" to null, "taskId" to state.taskId,
        )

        fun taskJson(state: RequirementState, category: String = state.category) = obj(
            "id" to state.taskId, "key" to "TASK-${state.index + 1}", "summary" to state.summary,
            "descriptionMarkdown" to state.description, "version" to state.taskVersion, "status" to status(category),
            "resolution" to obj("id" to uuid(600), "name" to "Completed"), "transitions" to JsonArray(if (state.allowStart) listOf(transition()) else emptyList()),
        )

        private fun specFragment() = obj(
            "id" to SPEC_ID, "key" to "SPEC-1", "metadataId" to SPEC_METADATA_ID, "programId" to null,
            "projectId" to PROJECT_ID, "ownerProfileId" to PRINCIPAL_ID, "parentSpecId" to null, "sortOrder" to 0,
            "childCount" to childCount, "childDoneCount" to 0, "gitRepositoryId" to null, "gitPath" to null,
            "watcherProfileIds" to array(), "labelIds" to array(), "deletedAt" to null,
            "createdAt" to "2026-10-09T12:00:00Z", "modifiedAt" to "2026-10-09T12:00:00Z",
            "createdByPrincipalId" to PRINCIPAL_ID, "modifiedByPrincipalId" to PRINCIPAL_ID, "version" to 1,
            "project" to obj("id" to PROJECT_ID, "key" to "WORK", "name" to "Work"), "status" to status("TODO"), "parentSpec" to null,
        )

        override fun close() { server.stop(0); network.http.dispatcher.executorService.shutdown(); network.http.connectionPool.evictAll() }
    }

    companion object {
        private fun qualityEvidence() = obj("review" to "Behavioral assertions cover results, empty input, denied access, error propagation, and document/task consistency through authenticated MCP. PostgreSQL rollback is tested in the owning service; tests use isolated fixtures without timing assumptions.",
            "coverage" to JsonArray(listOf(obj("scope" to "Affected search production services", "command" to "./gradlew :search:search:test :search:search:koverXmlReport :search:search:koverVerify",
                "report" to "search/search/build/reports/kover/report.xml", "lineCovered" to 990, "lineMissed" to 10,
                "branchCovered" to 970, "branchMissed" to 30, "uncovered" to "Report identifies remaining unreachable configuration guards; no changed behavior excluded"))))

        private fun interfaceReviewArgs() = obj("action" to "review_interfaces", "id" to SPEC_ID,
            "reviewNote" to "Reviewed search API and its caller before implementing behavior",
            "interfaces" to JsonArray(listOf(obj("path" to "search/core-search/Search.kt", "declaration" to "interface Search { suspend fun search(query: Query): Results }"))))

        private fun tddEvidence() = obj(
            "red" to obj("command" to "./gradlew :cli:test --tests SearchTest", "outcome" to "FAILED", "evidence" to "Expected matching result; empty result demonstrates missing behavior before implementation"),
            "green" to obj("command" to "./gradlew :cli:test --tests SearchTest", "outcome" to "PASSED", "evidence" to "Regression passes after minimal implementation"),
            "refactor" to obj("command" to "./gradlew :cli:test", "outcome" to "PASSED", "evidence" to "Suite passes after reviewing and simplifying implementation"),
        )

        private const val SPEC_ID = "00000000-0000-0000-0000-000000000001"
        private const val SPEC_METADATA_ID = "00000000-0000-0000-0000-000000000002"
        private const val PROJECT_ID = "00000000-0000-0000-0000-000000000003"
        private const val PRINCIPAL_ID = "00000000-0000-0000-0000-000000000004"
        private const val GENERATED_TASK_ID = "00000000-0000-0000-0000-000000000005"
        private const val CRITERION = "Valid behavior is preserved"
        private fun uuid(index: Int) = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"
        private fun obj(vararg entries: Pair<String, Any?>): JsonObject = buildJsonObject {
            entries.forEach { (key, value) -> put(key, when (value) {
                null -> JsonNull
                is JsonElement -> value
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> error("Unsupported fixture value: $value")
            }) }
        }
        private fun array(vararg values: String) = JsonArray(values.map(::JsonPrimitive))
        private fun status(category: String) = obj("id" to uuid(700), "name" to category, "category" to category)
        private fun transition() = obj("id" to uuid(800), "name" to "Begin", "description" to null, "toState" to obj("id" to uuid(801), "status" to status("IN_PROGRESS")))
        private fun requirementInput(criteria: List<String> = listOf(CRITERION)) = obj("name" to "Requirement", "markdownContent" to "Implement behavior", "acceptanceCriteria" to JsonArray(criteria.map(::JsonPrimitive)))
        private fun createArgs() = obj("action" to "create", "requestId" to uuid(998), "projectId" to PROJECT_ID, "name" to "Plan", "markdownContent" to "Current plan", "requirements" to JsonArray(listOf(requirementInput())))
        private fun documentTree(body: String, criteria: List<String>) = obj("document" to obj("type" to "doc", "content" to JsonArray(buildList {
            add(obj("type" to "paragraph", "content" to JsonArray(listOf(obj("type" to "text", "text" to body)))))
            if (criteria.isNotEmpty()) {
                add(obj("type" to "heading", "attrs" to obj("level" to 2), "content" to JsonArray(listOf(obj("type" to "text", "text" to "Acceptance criteria")))))
                add(obj("type" to "bulletList", "content" to JsonArray(criteria.map { criterion ->
                    obj("type" to "listItem", "content" to JsonArray(listOf(obj("type" to "paragraph", "content" to JsonArray(listOf(obj("type" to "text", "text" to criterion)))))))
                })))
            }
        })))
        private fun String.parseObject() = Json.parseToJsonElement(this).jsonObject
        private fun JsonObject.mapObject(vararg path: String, change: (JsonObject) -> JsonObject): JsonObject {
            fun replace(current: JsonObject, index: Int): JsonObject = if (index == path.size) change(current) else JsonObject(current + (path[index] to replace(current.getValue(path[index]).jsonObject, index + 1)))
            return replace(this, 0)
        }
        private fun JsonObject.operation() = getValue("operationName").jsonPrimitive.content
        private fun JsonObject.variables() = getValue("variables").jsonObject
        private fun CallToolResult.text() = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        private fun CallToolResult.output() = text().parseObject()
        private fun JsonObject.ready() = getValue("readyToComplete").jsonPrimitive.boolean
        private fun JsonObject.issues() = getValue("issues").jsonArray.map { it.jsonPrimitive.content }
    }
}
