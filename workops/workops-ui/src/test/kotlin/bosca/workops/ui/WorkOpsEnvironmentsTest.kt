package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsEnvironmentsTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun data(field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject("multiRepo") { put(field, value) } } }
    private val program get() = WorkOpsPlanningRow(id = "program", key = "PLAN", name = "Program")
    private val stage get() = WorkOpsEnvironmentTypeRecord("type", "Testing")
    private fun environment(id: String = "environment", revision: Long = 7, order: Int = 1) =
        WorkOpsEnvironmentRecord(id, "stable-key", "Testing", "Stored notes", order, true, false, "type", listOf("source"), revision,
            listOf(WorkOpsEnvironmentGrant("group", "EXECUTE")))
    private fun record(row: WorkOpsEnvironmentRecord) = buildJsonObject {
        put("id", row.id); put("key", row.key); put("name", row.name); put("description", row.description)
        put("displayOrder", row.order); put("requiresApproval", row.requiresApproval); put("autoPromote", row.autoPromote)
        put("typeId", row.typeId); put("promotionSourceIds", JsonArray(row.sources.map(::JsonPrimitive))); put("version", row.version)
        put("permissions", JsonArray(row.permissions.map { buildJsonObject { put("groupId", it.groupId); put("action", it.action) } }))
    }
    private fun programData() = buildJsonObject { putJsonObject("workOps") { putJsonObject("programs") { put("all", JsonArray(listOf(buildJsonObject {
        put("id", "program"); put("key", "PLAN"); put("name", "Program")
    }))) } } }
    private fun environments(rows: List<WorkOpsEnvironmentRecord>) = buildJsonObject { putJsonObject("workOps") { putJsonObject("multiRepo") {
        put("environments", JsonArray(rows.map(::record)))
        put("environmentTypes", JsonArray(listOf(buildJsonObject { put("id", "type"); put("name", "Testing"); put("description", JsonNull); put("displayOrder", 0); put("version", 1) })))
    } } }
    private fun groups(values: List<String>) = buildJsonObject { putJsonObject("security") { putJsonObject("groups") {
        put("all", JsonArray(values.map { buildJsonObject { put("id", it); put("name", "Group $it") } }))
    } } }
    private fun model(rows: List<WorkOpsEnvironmentRecord> = listOf(environment())) = WorkOpsEnvironmentsModel(
        programId = "program", programs = listOf(program), rows = rows, types = listOf(stage), loaded = true,
    )

    @Test fun `program selection defaults only when empty and does not query unavailable explicit programs`() = runTest {
        val model = WorkOpsEnvironmentsModel()
        model.reload(context { operation, _ -> when (operation) {
            "WorkOpsEnvironmentPrograms" -> programData()
            "WorkOpsProgramEnvironments" -> environments(emptyList())
            else -> error(operation)
        } })
        assertEquals("program", model.programId); assertTrue(model.available)
        val hidden = WorkOpsEnvironmentsModel("private")
        hidden.reload(context { operation, _ -> assertEquals("WorkOpsEnvironmentPrograms", operation); programData() })
        assertEquals("private", hidden.programId); assertNull(hidden.selectedProgram); assertFalse(hidden.available); assertTrue(hidden.rows.isEmpty())
    }

    @Test fun `environment sorting paging and unavailable references retain their program context`() = runTest {
        val model = WorkOpsEnvironmentsModel("program", targetId = "env-26")
        model.reload(context { operation, _ -> if (operation == "WorkOpsEnvironmentPrograms") programData() else environments((27 downTo 0).map { environment("env-$it", order = it) }) })
        assertEquals((0..27).toList(), model.rows.map { it.order }); assertEquals(25L, model.offset); assertEquals(3, model.pageRows.size)
        assertEquals("Environment unavailable", model.sourceNames(listOf("private"))); assertEquals("Type unavailable", model.typeName("private"))
        assertEquals("Group unavailable", model.groupName("private")); assertEquals("None", model.sourceNames(emptyList()))
        assertEquals("/workops/releases/environments?programId=program&offset=0", model.previousHref)
        model.resultId = "env-26"; assertTrue("programId=program&offset=0&item=env-26#workops-environment-env-26" in model.resultHref)
    }

    @Test fun `creation preserves native slug state and keeps a result after a failed refresh`() = runTest {
        val model = model(); model.open("", context { _, _ -> error("Creation does not load groups") })
        model.save(" QA ", " Explicit-Key ", " Notes ", "type", true, true, "", context { operation, variables ->
            if (operation == "WorkOpsCreateEnvironment") {
                val input = variables.getValue("input").jsonObject
                assertEquals("QA", input.getValue("name").jsonPrimitive.content); assertEquals("Explicit-Key", input.getValue("key").jsonPrimitive.content)
                assertEquals("program", input.getValue("programId").jsonPrimitive.content); assertEquals("Notes", input.getValue("description").jsonPrimitive.content)
                assertEquals(1, input.getValue("displayOrder").jsonPrimitive.int); assertTrue(input.getValue("autoPromote").jsonPrimitive.boolean)
                data("createEnvironment", record(environment("created")))
            } else error("Read unavailable")
        })
        assertTrue(model.draft.manualKey); assertEquals(" Explicit-Key ", model.draft.key)
        assertFalse(model.creating); assertNull(model.editing); assertFalse(model.loaded); assertTrue(model.feedback.failed)
        assertTrue("item=created" in model.resultHref)
    }

    @Test fun `choice and grant refreshes preserve inspected policy version immutable key and unsaved fields`() = runTest {
        val original = environment(); val model = model()
        model.open(original.id, context { _, _ -> error("Group catalog requires administrator") })
        assertTrue(model.groupFeedback.failed); assertTrue(model.available)
        val newer = original.copy(version = 9, requiresApproval = false, sources = listOf("different"))
        model.reloadDraft("Draft name", "forged-key", "Draft description", "type", true, true, "missing-group", context { operation, _ -> when (operation) {
            "WorkOpsEnvironmentPrograms" -> programData()
            "WorkOpsProgramEnvironments" -> environments(listOf(newer))
            else -> error("Groups unavailable")
        } })
        assertTrue(model.loaded); assertEquals(original, model.editing); assertEquals("stable-key", model.draft.key)
        assertEquals("Draft description", model.draft.description); assertTrue(model.groupChoices.single { it.value == "missing-group" }.disabled)
        model.save("Draft name", "forged-key", "Draft description", "type", true, true, "missing-group", context { operation, variables ->
            assertEquals("WorkOpsUpdateEnvironment", operation); assertEquals(7L, variables.getValue("version").jsonPrimitive.long)
            val input = variables.getValue("input").jsonObject
            assertFalse("key" in input); assertTrue(input.getValue("requiresApproval").jsonPrimitive.boolean)
            assertEquals(listOf("source"), input.getValue("promotionSourceIds").jsonArray.map { it.jsonPrimitive.content }); error("Newer environment")
        })
        assertTrue(model.feedback.failed); assertEquals(original, model.editing); assertTrue(model.draft.autoPromote)
    }

    @Test fun `a program manager can save environment fields when the administrator group catalog is unavailable`() = runTest {
        val model = model(); model.open("environment", context { _, _ -> error("Administrative access required") })
        val saved = environment(revision = 8).copy(name = "QA", description = "Notes", autoPromote = true)
        model.save("QA", "stable-key", "Notes", "type", true, true, "", context { operation, _ -> when (operation) {
            "WorkOpsUpdateEnvironment" -> data("updateEnvironment", record(saved))
            "WorkOpsEnvironmentPrograms" -> programData()
            "WorkOpsProgramEnvironments" -> environments(listOf(saved))
            else -> error(operation)
        } })
        assertFalse(model.feedback.failed); assertNull(model.editing); assertEquals(saved, model.rows.single())
        assertTrue(model.groupFeedback.failed); assertTrue("item=environment" in model.resultHref)
    }

    @Test fun `grant choices load existing offset pages and permission writes preserve unsaved environment edits`() = runTest {
        val original = environment(); val model = model(); val offsets = mutableListOf<Long>()
        model.open(original.id, context { operation, variables ->
            assertEquals("WorkOpsEnvironmentGroups", operation)
            val offset = variables.getValue("offset").jsonPrimitive.long; offsets += offset
            groups(if (offset == 0L) (0..99).map { "group-$it" } else listOf("last"))
        })
        assertEquals(listOf(0L, 100L), offsets); assertEquals(101, model.groups.size)
        val changed = original.copy(version = 9, permissions = original.permissions + WorkOpsEnvironmentGrant("last", "EXECUTE"))
        model.grant("Unsent name", "stable-key", "Unsent notes", "type", true, true, "last", context { operation, variables -> when (operation) {
            "WorkOpsGrantEnvironment" -> { assertEquals(setOf("id", "group"), variables.keys); assertEquals("last", variables.getValue("group").jsonPrimitive.content); data("addEnvironmentPermission", JsonPrimitive(true)) }
            "WorkOpsEnvironmentPrograms" -> programData()
            "WorkOpsProgramEnvironments" -> environments(listOf(changed))
            else -> error("Grant must not save environment fields")
        } })
        assertEquals("Unsent name", model.draft.name); assertEquals("Unsent notes", model.draft.description); assertTrue(model.draft.autoPromote)
        assertEquals(original, model.editing); assertEquals("", model.groupDraft); assertEquals(2, model.visibleGrants.size)
    }

    @Test fun `explicit grants can be removed without group labels and inherited program grants are not mutated`() = runTest {
        val model = model(); val original = environment()
        model.open(original.id, context { _, _ -> error("No global group catalog access") })
        assertEquals("Group unavailable", model.groupName("group"))
        model.revoke("group", "EXECUTE", "Draft", "changed-key", "Notes", "type", true, true, "", context { operation, variables -> when (operation) {
            "WorkOpsRevokeEnvironment" -> {
                assertEquals(setOf("id", "group", "action"), variables.keys); assertEquals("EXECUTE", variables.getValue("action").jsonPrimitive.content)
                data("removeEnvironmentPermission", JsonPrimitive(true))
            }
            "WorkOpsEnvironmentPrograms" -> programData()
            "WorkOpsProgramEnvironments" -> environments(listOf(original.copy(permissions = emptyList())))
            else -> error("Inherited program grants must not change")
        } })
        assertTrue(model.visibleGrants.isEmpty()); assertEquals(original, model.editing)
        assertEquals("Draft", model.draft.name); assertEquals("stable-key", model.draft.key); assertTrue(model.draft.autoPromote)
    }

    @Test fun `confirmed environment deletion sends only the id and retains rejected context`() = runTest {
        val model = model(); model.beginDelete("environment")
        model.delete(false, context { _, _ -> error("Must not write") }); assertEquals("Confirm deletion.", model.feedback.message)
        model.delete(true, context { operation, variables -> assertEquals("WorkOpsDeleteEnvironment", operation); assertEquals(setOf("id"), variables.keys); error("Denied") })
        assertEquals("environment", model.deleting?.id); assertTrue(model.feedback.failed)
        model.delete(true, context { operation, _ -> if (operation == "WorkOpsDeleteEnvironment") data("deleteEnvironment", JsonPrimitive(true)) else error("Read unavailable") })
        assertNull(model.deleting); assertFalse(model.loaded); assertEquals("program", model.programId)
    }

    @Test fun `cancellation propagates while invalid choices fail before calling the API`() = runTest {
        val model = model(); model.open("", context { _, _ -> error("No read") })
        var called = false
        model.save("QA", "qa", "Notes", "missing", false, false, "", context { _, _ -> called = true; error("Must not write") })
        assertFalse(called); assertEquals("missing", model.draft.typeId); assertTrue(model.creating)
        assertFailsWith<CancellationException> { model.open("environment", context { _, _ -> throw CancellationException("Navigation") }) }
    }
}
