package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsAutomationTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: buildJsonObject {})
    })
    private fun data(field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject("automation") { put(field, value) } } }
    private fun row(id: String = "rule", version: Long = 7, scope: String = "PROJECT", scopeId: String? = "project") = buildJsonObject {
        put("id", id); put("name", "Rule $id"); put("description", "Stored notes"); put("enabled", false); put("scope", scope); put("scopeId", scopeId?.let(::JsonPrimitive) ?: JsonNull)
        putJsonObject("trigger") { put("type", "TaskUpdated"); putJsonArray("fieldKeys") { add("summary") } }
        put("conditions", JsonArray(emptyList())); put("actions", JsonArray(emptyList())); put("failureMode", "CONTINUE")
        put("maxFiresPerTaskPerHour", 8); put("executionLogRetentionDays", 60); put("runAsProfileId", "stored-profile"); put("version", version)
    }
    private val raw = WorkOpsAutomationRuleRecord("rule", "Stored rule", scope = "PROJECT", scopeId = "project", trigger = "{\"type\":\"TaskUpdated\",\"fieldKeys\":[\"summary\"]}", runAsId = "stored-profile", version = 7)
    private val profileFailure = context { _, _ -> error("Profile read unavailable") }
    private suspend fun save(model: WorkOpsAutomationModel, ctx: RenderContext, name: String = " Draft rule ", trigger: String = model.draft.trigger, conditions: String = "[]", actions: String = "[]", scope: String = model.draft.scope, id: String = model.draft.scopeId, fires: String = "9", retention: String = "45") =
        model.save(name, " Draft notes ", false, scope, id, trigger, conditions, actions, "CONTINUE", fires, retention, "profile", ctx)

    @Test fun `scope reads retain exact identity and target offset pages and distinguish empty from unavailable`() = runTest {
        for (scope in listOf("GLOBAL", "PORTFOLIO", "PROGRAM", "PROJECT")) {
            val id = if (scope == "GLOBAL") "" else "selected"
            val model = WorkOpsAutomationModel(scope, id, targetId = "rule-26")
            model.retry(context { _, variables ->
                assertEquals(scope, variables.getValue("scope").jsonPrimitive.content)
                assertEquals(if (id.isBlank()) JsonNull else JsonPrimitive(id), variables["scopeId"] ?: JsonNull)
                data("rules", JsonArray((0..27).map { row("rule-$it", scope = scope, scopeId = id.takeIf(String::isNotBlank)) }))
            })
            assertTrue(model.loaded); assertEquals(25L, model.offset); assertEquals(3, model.pageRows.size)
            assertTrue("scope=$scope" in model.previousHref); assertTrue("scopeId=$id" in model.previousHref)
            model.retry(context { _, _ -> error("Read denied") }); assertFalse(model.loaded); assertTrue(model.feedback.failed)
            model.retry(context { _, _ -> data("rules", JsonArray(emptyList())) }); assertTrue(model.loaded); assertTrue(model.rows.isEmpty()); assertFalse(model.feedback.failed)
        }
    }

    @Test fun `missing or contradictory scope identifiers fail before reading`() = runTest {
        for ((scope, id) in listOf("GLOBAL" to "project", "PROJECT" to "", "PORTFOLIO" to "", "PROGRAM" to "", "UNKNOWN" to "")) {
            val model = WorkOpsAutomationModel(scope, id)
            model.retry(context { _, _ -> error("Must not read") }); assertFalse(model.loaded); assertTrue(model.feedback.failed); assertFalse(model.feedback.message.contains("Must not read"))
        }
    }

    @Test fun `edits preserve stored scope and inspected version across read failures and catalog reloads`() = runTest {
        val model = WorkOpsAutomationModel("PROJECT", "project", rows = listOf(raw), loaded = true)
        model.open("rule", profileFailure); assertTrue(model.editing); assertTrue(model.profileFeedback.failed)
        model.reloadDraft(" Unsent rule ", " Draft notes ", false, "GLOBAL", "", "{\"type\":\"TaskUpdated\"}", "[]", "[]", "CONTINUE", "9", "45", "profile", context { operation, _ ->
            if (operation == "WorkOpsAutomationRules") data("rules", JsonArray(listOf(row(version = 9)))) else error("Profile unavailable")
        })
        assertEquals(7L, model.draft.version); assertEquals(9L, model.rows.single().version); assertEquals("PROJECT", model.draft.scope); assertEquals("project", model.draft.scopeId)
        save(model, context { operation, variables ->
            assertEquals("WorkOpsAutomationUpdate", operation); assertEquals(7L, variables.getValue("version").jsonPrimitive.long)
            val input = variables.getValue("input").jsonObject; assertEquals("PROJECT", input.getValue("scope").jsonPrimitive.content); assertEquals("project", input.getValue("scopeId").jsonPrimitive.content)
            assertEquals("Draft rule", input.getValue("name").jsonPrimitive.content); assertEquals("Draft notes", input.getValue("description").jsonPrimitive.content)
            assertEquals(9, input.getValue("maxFiresPerTaskPerHour").jsonPrimitive.int); assertEquals(45, input.getValue("executionLogRetentionDays").jsonPrimitive.int)
            assertEquals("CONTINUE", input.getValue("failureMode").jsonPrimitive.content); assertFalse(input.getValue("enabled").jsonPrimitive.boolean)
            error("Stale rule")
        }, scope = "GLOBAL", id = "")
        assertTrue(model.editing); assertTrue(model.feedback.failed); assertEquals(" Draft rule ", model.draft.name); assertEquals(" Draft notes ", model.draft.description)
    }

    @Test fun `trigger changes preserve extra keys and raw unrelated drafts and reject invalid JSON without resetting it`() = runTest {
        val model = WorkOpsAutomationModel(rows = listOf(raw), loaded = true); model.open("rule", profileFailure)
        model.chooseTrigger("TaskTransitioned", " Unsent ", " Notes ", false, "PROJECT", "project", raw.trigger, " broken conditions ", " broken actions ", "CONTINUE", "8", "12", "profile", profileFailure)
        assertEquals("TaskTransitioned", model.draft.triggerType); assertEquals(JsonPrimitive("summary"), Json.parseToJsonElement(model.draft.trigger).jsonObject.getValue("fieldKeys").jsonArray.single())
        assertEquals(" broken conditions ", model.draft.conditions); assertEquals(" broken actions ", model.draft.actions); assertEquals(" Unsent ", model.draft.name)
        val revision = model.draftRevision
        model.chooseTrigger("TaskCreated", "Other name", " Notes ", true, "PROJECT", "project", "{ bad trigger", "[]", "[]", "CONTINUE", "8", "12", "profile", profileFailure)
        assertTrue(model.feedback.failed); assertEquals("{ bad trigger", model.draft.trigger); assertEquals("Other name", model.draft.name); assertEquals(revision, model.draftRevision)
        model.draft = model.draft.copy(trigger = "{\"type\":\"ArtifactPublished\",\"artifactTypes\":[\"npm\"]}")
        assertTrue(model.triggerChoices.any { it.value == "ArtifactPublished" })
    }

    @Test fun `invalid JSON shapes and positive limits retain drafts and never write`() = runTest {
        val model = WorkOpsAutomationModel(loaded = true); model.open("", profileFailure)
        var writes = 0; val ctx = context { _, _ -> writes++; error("Must not write") }
        for (text in listOf("{ invalid", "[]")) { save(model, ctx, trigger = text); assertTrue(model.feedback.failed); assertEquals(text, model.draft.trigger) }
        save(model, ctx, trigger = "{}", conditions = "{}"); assertEquals("Conditions must be a JSON array.", model.feedback.message)
        save(model, ctx, trigger = "{}", actions = "false"); assertEquals("Actions must be a JSON array.", model.feedback.message)
        for (count in listOf("0", "-1", "1.5", "", "bad", "2147483648")) {
            save(model, ctx, fires = count); assertEquals("Enter a positive fire limit.", model.feedback.message); assertEquals(count, model.draft.fires)
            save(model, ctx, retention = count); assertEquals("Enter a positive log retention period.", model.feedback.message); assertEquals(count, model.draft.retention)
        }
        assertEquals(0, writes); assertTrue(model.editing)
    }

    @Test fun `accepted creation keeps its different scope and result identity after readback fails and cannot repeat`() = runTest {
        val model = WorkOpsAutomationModel(loaded = true); model.open("", profileFailure)
        var writes = 0
        val ctx = context { operation, variables ->
            if (operation == "WorkOpsAutomationCreate") {
                writes++; val input = variables.getValue("input").jsonObject
                assertEquals("PROGRAM", input.getValue("scope").jsonPrimitive.content); assertEquals("program", input.getValue("scopeId").jsonPrimitive.content)
                assertEquals("TaskCreated", input.getValue("trigger").jsonObject.getValue("type").jsonPrimitive.content)
                data("create", buildJsonObject { put("id", "created") })
            } else error("Read unavailable")
        }
        save(model, ctx, scope = "PROGRAM", id = "program"); assertFalse(model.editing); assertFalse(model.loaded); assertTrue(model.feedback.failed)
        assertTrue("scope=PROGRAM&scopeId=program" in model.resultHref); assertTrue("item=created" in model.resultHref)
        save(model, ctx); assertEquals(1, writes)
    }

    @Test fun `enable and deletion use captured identities and keep rejected state without optimistic local changes`() = runTest {
        val model = WorkOpsAutomationModel(rows = listOf(raw), loaded = true)
        model.toggle(raw.id, false, raw.version, context { operation, variables ->
            assertEquals("WorkOpsAutomationSetEnabled", operation); assertEquals(7L, variables.getValue("version").jsonPrimitive.long); assertFalse(variables.getValue("enabled").jsonPrimitive.boolean); error("Denied toggle")
        })
        assertEquals(raw, model.rows.single()); assertTrue(model.feedback.failed)
        model.beginDelete(raw.id); model.delete(false, context { _, _ -> error("Must not write") }); assertEquals("Confirm deletion.", model.feedback.message)
        model.delete(true, context { operation, variables -> assertEquals("WorkOpsAutomationDelete", operation); assertEquals(setOf("id"), variables.keys); error("Denied deletion") })
        assertEquals(raw, model.deleting)
        var writes = 0
        val ctx = context { operation, _ -> if (operation == "WorkOpsAutomationDelete") { writes++; data("delete", JsonPrimitive(true)) } else error("Read unavailable") }
        model.delete(true, ctx); assertNull(model.deleting); assertFalse(model.loaded); model.delete(true, ctx); assertEquals(1, writes)
    }

    @Test fun `history paginates actual facts independently and does not turn a denied read into empty results`() = runTest {
        val model = WorkOpsAutomationModel(rows = listOf(raw), loaded = true)
        val ctx = context { operation, variables ->
            assertEquals("WorkOpsAutomationHistory", operation); assertEquals("rule", variables.getValue("id").jsonPrimitive.content)
            data("executionLogs", JsonArray((0..50).map { buildJsonObject {
                put("id", "log-$it"); put("outcome", if (it == 0) "OK" else "ACTION_FAILED"); put("startedAt", "2026-11-01T10:00:00Z"); put("finishedAt", JsonNull)
                put("durationMs", if (it == 0) JsonPrimitive(0) else JsonNull); put("errorMessage", if (it == 0) JsonNull else JsonPrimitive("<failure>")); put("taskId", "task")
            } }))
        }
        model.openHistory(raw.id, ctx); assertTrue(model.historyLoaded); assertTrue(model.historyHasNext); assertEquals(50, model.historyRows.size)
        assertEquals(0L, model.historyRows.first().duration); assertNull(model.historyRows[1].duration); assertEquals("<failure>", model.historyRows[1].error)
        assertEquals("/workops/tasks/task", model.historyRows.first().taskHref); assertEquals("", model.historyRows.first().finished)
        model.historyPage(true, context { _, variables -> assertEquals(50L, variables.getValue("offset").jsonPrimitive.long); error("History denied") })
        assertFalse(model.historyLoaded); assertTrue(model.historyFeedback.failed); assertFalse(model.feedback.failed); assertEquals(50, model.historyRows.size)
        model.retryHistory(context { _, _ -> data("executionLogs", JsonArray(emptyList())) }); assertTrue(model.historyLoaded); assertTrue(model.historyRows.isEmpty()); assertFalse(model.historyHasNext)
    }

    @Test fun `cancellation propagates through catalog and independent history reads`() = runTest {
        val model = WorkOpsAutomationModel(rows = listOf(raw), loaded = true)
        val ctx = context { _, _ -> throw CancellationException("Navigation") }
        assertFailsWith<CancellationException> { model.retry(ctx) }; assertFalse(model.loaded)
        model.loaded = true; assertFailsWith<CancellationException> { model.openHistory(raw.id, ctx) }; assertFalse(model.historyLoaded)
    }
    @Test fun `page refresh resolves named scope choices and keeps rule drafts and cancellation`() = runTest {
        for (scope in listOf("GLOBAL", "PORTFOLIO", "PROGRAM", "PROJECT")) {
            val model = WorkOpsAutomationModel(scope, if (scope == "GLOBAL") "" else "entity", draft = raw, editing = true)
            model.refresh(context { operation, _ ->
                if (operation == "WorkOpsAutomationRules") data("rules", JsonArray(emptyList()))
                else buildJsonObject { putJsonObject("workOps") { putJsonObject(scope.lowercase() + "s") {
                    putJsonArray("all") { add(buildJsonObject { put("id", "entity"); put("key", "ENT"); put("name", "Named entity") }) }
                } } }
            })
            assertTrue(model.loaded); assertTrue(model.editing); assertEquals(raw, model.draft)
            if (scope != "GLOBAL") assertEquals("Named entity", model.scopeChoices.entities.single().name)
        }
        val failed = WorkOpsAutomationModel("PROJECT", "entity")
        failed.refresh(context { operation, _ -> if (operation == "WorkOpsAutomationRules") data("rules", JsonArray(emptyList())) else error("Catalog unavailable") })
        assertTrue(failed.loaded); assertTrue(failed.scopeChoices.choiceFeedback.failed)
        assertFailsWith<CancellationException> { failed.refresh(context { _, _ -> throw CancellationException("Navigation") }) }
    }

}
