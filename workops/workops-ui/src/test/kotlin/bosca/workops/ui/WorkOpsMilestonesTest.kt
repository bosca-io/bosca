package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.withRenderContext
import bosca.workops.ui.graphql.WorkOpsMilestonePrograms
import bosca.workops.ui.graphql.WorkOpsMilestones
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsMilestonesTest {
    private fun json(value: String) = Json.parseToJsonElement(value)
    private val programs = json("""{"workOps":{"programs":{"all":[{"id":"program","key":"PLAN","name":"Planning program","archivedAt":null},{"id":"archived","key":"OLD","name":"Archived program","archivedAt":"2026-01-01T00:00:00Z"}]}}}""")
    private fun milestones(count: Int = 1, version: Long = 3) = buildJsonObject {
        putJsonObject("workOps") { putJsonObject("milestones") { putJsonArray("byProgram") {
            repeat(count) { addJsonObject {
                put("id", "milestone-$it"); put("name", "Milestone $it"); put("description", "A commitment")
                put("targetDate", "2027-02-03T00:00:00Z"); put("state", "OPEN"); put("closedAt", JsonNull); put("version", version)
            } }
        } } }
    }
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun model() = WorkOpsMilestonesModel(programId = "program",
        programs = WorkOpsMilestonePrograms.decodeData(programs), data = WorkOpsMilestones.decodeData(milestones()))

    @Test fun `settings and planning entry points preserve program and offset without querying an inaccessible program`() = runTest {
        val ctx = context { operation, _ -> assertEquals("WorkOpsMilestonePrograms", operation); programs }
        val page = withRenderContext(RenderContext(gql = ctx.gql,
            query = mapOf("programId" to "private", "offset" to "25")).apply { requestPath = "/workops/settings/milestones" }) { workOpsMilestones() }
        assertTrue(page.feedback.failed); assertEquals("private", page.programId); assertTrue(page.rows.isEmpty())
        assertEquals("/workops/settings/milestones?programId=private&offset=0", page.previousHref)
        assertEquals("The selected program is unavailable. Choose another program.", page.feedback.message)
    }

    @Test fun `creation uses a UTC date and retains every field when the owning API rejects access`() = runTest {
        val page = model().apply { open() }
        page.create("Release readiness", "Review the release", "2027-02-03", "archived", context { operation, variables ->
            assertEquals("WorkOpsCreateMilestone", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals("2027-02-03T00:00Z", input.getValue("targetDate").jsonPrimitive.content)
            assertEquals("archived", input.getValue("programId").jsonPrimitive.content)
            error("Unauthorized access")
        })
        assertTrue(page.creating); assertTrue(page.feedback.failed); assertEquals("Release readiness", page.nameDraft)
        assertEquals("Review the release", page.descriptionDraft); assertEquals("2027-02-03", page.dateDraft)
        assertEquals("archived", page.createProgramId)
        page.create("Name", "Description", "invalid-date", "program", context { operation, _ -> error(operation) })
        assertTrue(page.feedback.failed); assertEquals("invalid-date", page.dateDraft)
        page.create("Name", "Description", "", "private", context { operation, _ -> error(operation) })
        assertEquals("Choose an available program.", page.feedback.message)
    }

    @Test fun `successful creation reloads its selected program and keeps an omitted date null`() = runTest {
        val page = model().apply { open() }
        page.create("New milestone", "", "", "archived", context { operation, variables -> when (operation) {
            "WorkOpsCreateMilestone" -> {
                val input = variables.getValue("input").jsonObject
                assertEquals(JsonNull, input["targetDate"] ?: JsonNull)
                json("""{"workOps":{"milestones":{"create":{"id":"created"}}}}""")
            }
            "WorkOpsMilestonePrograms" -> programs
            "WorkOpsMilestones" -> { assertEquals("archived", variables.getValue("programId").jsonPrimitive.content); milestones() }
            else -> error(operation)
        } })
        assertFalse(page.creating); assertFalse(page.feedback.failed); assertEquals("archived", page.programId)
        assertEquals("Milestone created.", page.feedback.message)
    }

    @Test fun `confirmed close retains the inspected version after a refresh and a rejected close`() = runTest {
        val page = model().apply { beginClose("milestone-0") }
        page.data = WorkOpsMilestones.decodeData(milestones(version = 7))
        page.close(false, context { operation, _ -> error(operation) })
        assertEquals("Confirm closing this milestone.", page.feedback.message)
        page.close(true, context { operation, variables ->
            assertEquals("WorkOpsCloseMilestone", operation)
            assertEquals(3L, variables.getValue("version").jsonPrimitive.long)
            error("A newer milestone was saved")
        })
        assertTrue(page.feedback.failed); assertEquals("milestone-0", page.closingId); assertEquals(3L, page.closeVersion)
        page.cancelClose(); assertEquals("", page.closingId)
    }

    @Test fun `offset pages retain program context and transport errors do not become empty lists`() = runTest {
        val page = WorkOpsMilestonesModel(programId = "program", offset = 25)
        page.reload(context { operation, _ -> if (operation == "WorkOpsMilestonePrograms") programs else milestones(51) })
        assertEquals(25, page.rows.size); assertEquals("milestone-25", page.rows.first().id)
        assertEquals("/workops/milestones?programId=program&offset=50", page.nextHref)
        page.retry(context { _, _ -> error("Service unavailable") })
        assertTrue(page.feedback.failed); assertEquals("Service unavailable", page.feedback.message)
        assertFailsWith<CancellationException> { page.retry(context { _, _ -> throw CancellationException("left page") }) }
    }

    @Test fun `due labels compare calendar dates and omit closed milestone warnings`() {
        val page = model(); val today = LocalDate.of(2026, 11, 14)
        assertEquals("1 day overdue", page.dueLabel("2026-11-13T00:00:00Z", false, today))
        assertEquals("Due today", page.dueLabel("2026-11-14T00:00:00Z", false, today))
        assertEquals("1 day left", page.dueLabel("2026-11-15T00:00:00Z", false, today))
        assertEquals("", page.dueLabel("2026-11-13T00:00:00Z", true, today))
    }
}
