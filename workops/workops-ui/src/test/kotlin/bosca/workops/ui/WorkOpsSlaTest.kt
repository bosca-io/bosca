package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsSlaTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: buildJsonObject {})
    })
    private fun data(field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject("sla") { put(field, value) } } }
    private val weekly = buildJsonObject { putJsonArray("MONDAY") { add(buildJsonObject { put("startLocal", "09:30"); put("endLocal", "24:00") }) }; put("extra", "Stored field") }
    private val dates = JsonArray(listOf(JsonPrimitive("2026-12-25")))
    private fun catalog(count: Int = 1) = buildJsonObject { putJsonObject("workOps") { putJsonObject("sla") {
        putJsonArray("slaPolicies") { repeat(count) { n -> add(buildJsonObject { put("id", "policy-$n"); put("name", "Policy $n"); put("description", JsonNull); putJsonArray("goals") {
            add(buildJsonObject { put("id", "goal"); put("name", "Response"); put("targetMinutes", 125); put("atRiskAtPercent", 85); put("startConditions", "open"); put("stopConditions", "done"); put("pauseConditions", "paused"); put("displayOrder", 10) })
        } }) } }
        putJsonArray("workingCalendars") { repeat(count) { n -> add(buildJsonObject { put("id", "calendar-$n"); put("name", "Calendar $n"); put("description", "Stored notes"); put("timeZone", "Pacific/Auckland"); put("weeklyHours", weekly); put("holidays", dates) }) } }
    } } }
    private suspend fun save(model: WorkOpsSlaModel, ctx: RenderContext, name: String = " Draft name ", zone: String = " Pacific/Auckland ", hours: String = weekly.toString(), holidays: String = dates.toString()) = model.save(name, " Draft notes ", zone, hours, holidays, ctx)

    @Test fun `catalog maps exact JSON goals and offset targets and distinguishes unavailable from empty`() = runTest {
        for (kind in listOf("policies", "calendars")) {
            val model = WorkOpsSlaModel(kind, targetId = if (kind == "calendars") "calendar-26" else "policy-26")
            model.retry(context { op, _ -> assertEquals("WorkOpsSlaCatalog", op); catalog(28) })
            assertTrue(model.loaded); assertEquals(25L, model.offset); assertEquals(3, if (kind == "calendars") model.pageCalendars.size else model.pagePolicies.size)
            assertTrue("tab=$kind" in model.previousHref); assertEquals("", model.nextHref); assertEquals(1, model.tabs.count { it.selected })
            assertEquals(weekly, model.calendars.first().weeklyHours); assertEquals(dates, model.calendars.first().holidays)
            val goal = model.policies.first().goals.single(); assertEquals("2h 5m", goal.duration); assertEquals(85, goal.risk); assertEquals("paused", goal.pause)
            model.retry(context { _, _ -> error("Read denied") }); assertFalse(model.loaded); assertTrue(model.feedback.failed)
            model.retry(context { _, _ -> catalog(0) }); assertTrue(model.loaded); assertTrue(model.policies.isEmpty()); assertTrue(model.calendars.isEmpty()); assertFalse(model.feedback.failed)
        }
    }

    @Test fun `policy creation submits header fields only and retained retries preserve raw drafts`() = runTest {
        val model = WorkOpsSlaModel(loaded = true); model.open()
        save(model, context { op, variables ->
            assertEquals("WorkOpsSlaCreatePolicy", op); assertEquals(setOf("name", "description"), variables.keys)
            assertEquals("Draft name", variables.getValue("name").jsonPrimitive.content); assertEquals("Draft notes", variables.getValue("description").jsonPrimitive.content); error("Denied")
        }, hours = "not used")
        assertTrue(model.editing); assertTrue(model.feedback.failed); assertEquals(" Draft name ", model.draft.name); assertEquals("not used", model.draft.weeklyHours)
    }

    @Test fun `calendar writes structured values and retains API-rejected timezone and raw JSON`() = runTest {
        val model = WorkOpsSlaModel("calendars", loaded = true); model.open()
        save(model, context { op, variables ->
            assertEquals("WorkOpsSlaCreateCalendar", op); val input = variables.getValue("input").jsonObject
            assertEquals("Pacific/Auckland", input.getValue("timeZone").jsonPrimitive.content)
            assertEquals("Draft name", input.getValue("name").jsonPrimitive.content); assertEquals(weekly, input.getValue("weeklyHours")); assertEquals(dates, input.getValue("holidays")); error("Denied")
        })
        assertTrue(model.editing); assertEquals(" Pacific/Auckland ", model.draft.timeZone); assertEquals(weekly.toString(), model.draft.weeklyHours)
        save(model, context { _, _ -> error("Invalid time zone") }, zone = "Unknown/Place")
        assertEquals("Unknown/Place", model.draft.timeZone); assertTrue(model.feedback.message.contains("Invalid time zone"))
    }

    @Test fun `invalid names JSON shapes and raw syntax never write or discard the submitted draft`() = runTest {
        val model = WorkOpsSlaModel("calendars", loaded = true); model.open(); var writes = 0
        val ctx = context { _, _ -> writes++; error("Must not write") }
        save(model, ctx, name = " "); assertEquals("Enter a name.", model.feedback.message)
        save(model, ctx, zone = " "); assertEquals("Enter a time zone.", model.feedback.message)
        save(model, ctx, hours = "[ invalid"); assertEquals("[ invalid", model.draft.weeklyHours); assertTrue(model.feedback.failed)
        save(model, ctx, hours = "[]"); assertEquals("Weekly hours must be a JSON object.", model.feedback.message)
        save(model, ctx, holidays = "{ invalid"); assertEquals("{ invalid", model.draft.holidays)
        save(model, ctx, holidays = "{}"); assertEquals("Holidays must be a JSON array.", model.feedback.message)
        assertEquals(0, writes); assertTrue(model.editing)
    }

    @Test fun `reload keeps all draft fields and JSON engine revision across failed and recovered reads`() = runTest {
        val model = WorkOpsSlaModel("calendars", loaded = true); model.open(); val revision = model.draftRevision
        model.reloadDraft("Unsent", "Notes", "Unknown/Zone", " invalid hours ", " invalid dates ", context { _, _ -> error("Read denied") })
        assertFalse(model.loaded); assertEquals(" invalid hours ", model.draft.weeklyHours); assertEquals(" invalid dates ", model.draft.holidays); assertEquals(revision, model.draftRevision)
        model.retry(context { _, _ -> catalog() }); assertTrue(model.loaded); assertEquals("Unsent", model.draft.name); assertEquals("Unknown/Zone", model.draft.timeZone)
        model.cancel(); model.open(); assertEquals(revision + 1, model.draftRevision); assertEquals(WorkOpsSlaDraft(), model.draft)
    }

    @Test fun `accepted create retains result identity after readback fails and cannot repeat`() = runTest {
        for (kind in listOf("policies", "calendars")) {
            val model = WorkOpsSlaModel(kind, loaded = true); model.open(); var writes = 0
            val operation = if (kind == "calendars") "WorkOpsSlaCreateCalendar" else "WorkOpsSlaCreatePolicy"
            val field = if (kind == "calendars") "createWorkingCalendar" else "createSlaPolicy"
            val ctx = context { op, _ -> if (op == operation) { writes++; data(field, buildJsonObject { put("id", "created") }) } else error("Read failed") }
            save(model, ctx); assertFalse(model.editing); assertFalse(model.loaded); assertTrue(model.feedback.failed)
            assertTrue("tab=$kind" in model.resultHref); assertTrue("item=created" in model.resultHref); save(model, ctx); assertEquals(1, writes)
        }
    }

    @Test fun `readonly details retain supplied conditions order minute formats and calendar values`() = runTest {
        val model = WorkOpsSlaModel(); model.retry(context { _, _ -> catalog() }); model.view("policy-0")
        assertEquals("open", model.policyDetail?.goals?.single()?.start); model.closeDetail(); assertNull(model.policyDetail)
        val goal = model.policies.single().goals.single()
        assertEquals(listOf("45m", "1h", "1h 1m", "0m"), listOf(45, 60, 61, 0).map { goal.copy(minutes = it).duration })
        val policy = model.policies.single().copy(goals = listOf(goal.copy(name = "Later", order = 2), goal.copy(name = "B", order = 1), goal.copy(name = "A", order = 1)))
        assertEquals(listOf("A", "B", "Later"), policy.orderedGoals.map { it.name })
        val calendars = WorkOpsSlaModel("calendars"); calendars.retry(context { _, _ -> catalog() }); calendars.view("calendar-0")
        assertEquals(weekly, calendars.calendarDetail?.weeklyHours); assertEquals(dates, calendars.calendarDetail?.holidays); calendars.closeDetail(); assertNull(calendars.calendarDetail)
    }

    @Test fun `cancellation propagates without a successful empty read`() = runTest {
        val model = WorkOpsSlaModel(loaded = true); assertFailsWith<CancellationException> { model.retry(context { _, _ -> throw CancellationException("Navigation") }) }; assertFalse(model.loaded)
    }
}
