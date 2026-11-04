package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.system.ui.graphql.SystemDefinitionsData
import bosca.system.ui.generated.SystemScheduleComponent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ScheduleActionsTest {
    private val definition = SystemDefinitionsData.Scheduler.AvailableJobDefinitions("purge-job-history", "purge-job-history", "Purge Job History", "common", Json.parseToJsonElement("""{"type":"object","properties":{"retentionDays":{"type":"integer"}}}"""))
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })

    @Test fun `schema disclosure renders supplied metadata and stays absent for schema less definitions`() = runTest {
        for (hasSchema in listOf(true, false)) {
            val model = SystemScheduleModel("new", true, jobName = definition.name, definitions = listOf(if (hasSchema) definition else definition.copy(parameterSchema = null)))
            val ctx = RenderContext()
            SystemScheduleComponent.render(ctx, mapOf("initial" to model)) {}
            assertEquals(hasSchema, "data-parameter-schema" in ctx.writer.toString())
            if (hasSchema) assertTrue("retentionDays" in ctx.writer.toString())
        }
    }

    @Test fun `definition and cron inspection retain every unsaved field and expose preview results`() = runTest {
        val model = SystemScheduleModel("new", true, definitions = listOf(definition))
        model.inspect("Draft", "Details", definition.name, "{\"retentionDays\":41}", "0 2 * * *", false, true, true, "4", true, context { operation, variables ->
            assertEquals("SystemCron", operation)
            assertEquals("0 2 * * *", variables.getValue("expression").jsonPrimitive.content)
            Json.parseToJsonElement("""{"scheduler":{"validateCronExpression":{"valid":true,"error":null,"nextRuns":["2026-11-03T02:00:00Z"]}}}""")
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals("Draft", model.name)
        assertEquals("Details", model.description)
        assertEquals("{\"retentionDays\":41}", model.parameters)
        assertFalse(model.enabled)
        assertTrue(model.concurrent && model.catchUp)
        assertEquals("4", model.maxCatchUp)
        assertEquals("common", model.selectedDefinition?.queueName)
        assertNotNull(model.selectedDefinition?.parameterSchema)
        assertEquals(1, model.cronCheck?.nextRuns?.size)
        model.inspect("Draft", "Details", definition.name, model.parameters, "bad", false, true, true, "4", false, context { _, _ -> error("Must not query") })
        assertNull(model.cronCheck)
    }

    @Test fun `creation requires a validated cron and preserves JSON parameters`() = runTest {
        val model = SystemScheduleModel("new", true, definitions = listOf(definition))
        model.save(" Scheduled ", " Description ", definition.name, "{\"retentionDays\":41}", " 0 2 * * * ", true, false, false, "", context { operation, variables ->
            when (operation) {
                "SystemCron" -> {
                    assertEquals("0 2 * * *", variables.getValue("expression").jsonPrimitive.content)
                    Json.parseToJsonElement("""{"scheduler":{"validateCronExpression":{"valid":true,"error":null,"nextRuns":[]}}}""")
                }
                "SystemCreateSchedule" -> {
                    val input = variables.getValue("input").jsonObject
                    assertEquals("0 2 * * *", input.getValue("cronExpression").jsonPrimitive.content)
                    assertTrue(input["maxCatchUp"] == null || input["maxCatchUp"] == JsonNull)
                    assertEquals(41, input.getValue("jobParameters").jsonObject.getValue("retentionDays").jsonPrimitive.int)
                    Json.parseToJsonElement("""{"scheduler":{"create":{"id":"created"}}}""")
                }
                else -> error(operation)
            }
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals("/system/scheduler/created", model.feedback.nextHref)
    }

    @Test fun `blank cron cannot create a schedule or clear an existing schedule`() = runTest {
        for (id in listOf("new", "schedule")) for (cron in listOf("", "   ")) {
            val model = SystemScheduleModel(id, true, definitions = listOf(definition))
            model.save("Name", "Draft", definition.name, "{}", cron, true, false, false, "", context { _, _ -> error("Must not query or save") })
            assertTrue(model.feedback.failed)
            assertEquals("Enter a cron expression.", model.feedback.message)
            assertEquals("Draft", model.description)
            assertEquals(cron, model.cron)
        }
    }

    @Test fun `invalid catchup JSON and empty cron preview preserve drafts without writes`() = runTest {
        val model = SystemScheduleModel("new", true, definitions = listOf(definition))
        val noQuery = context { _, _ -> error("Must not query") }
        model.save("Name", "", definition.name, "{}", "0 2 * * *", true, false, true, "bad", noQuery)
        assertEquals("bad", model.maxCatchUp)
        assertTrue(model.feedback.failed)
        model.save("Name", "", definition.name, "{invalid", "0 2 * * *", true, false, true, "3", context { operation, _ ->
            assertEquals("SystemCron", operation)
            Json.parseToJsonElement("""{"scheduler":{"validateCronExpression":{"valid":true,"error":null,"nextRuns":[]}}}""")
        })
        assertTrue(model.feedback.failed)
        assertEquals("{invalid", model.parameters)
        model.inspect("Name", "", definition.name, "{}", "", true, false, false, "", true, noQuery)
        assertEquals("Cron expression is empty.", model.feedback.message)
    }

    @Test fun `history paging passes schedule identity and both filters`() = runTest {
        val model = SystemScheduleModel("schedule", true)
        model.loadHistory("FAILED", "EVENT", 25, context { operation, variables ->
            assertEquals("SystemScheduleHistory", operation)
            assertEquals("schedule", variables.getValue("id").jsonPrimitive.content)
            assertEquals(25, variables.getValue("offset").jsonPrimitive.int)
            assertEquals("FAILED", variables.getValue("status").jsonPrimitive.content)
            assertEquals("EVENT", variables.getValue("source").jsonPrimitive.content)
            Json.parseToJsonElement("""{"scheduler":{"job":{"history":[],"historyCount":30}}}""")
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals(30, model.historyCount)
        assertEquals(25, model.historyOffset)
        model.action("delete", false, context { _, _ -> error("Must not write") })
        assertTrue(model.feedback.failed)
    }

    @Test fun `failed assignment retains its draft separately from the stored principal`() = runTest {
        val model = SystemScheduleModel("schedule", true, principalId = "stored")
        model.assign("draft", false, true, context { _, _ -> error("Principal was not found") })
        assertTrue(model.feedback.failed)
        assertEquals("draft", model.principalDraftId)
        assertEquals("stored", model.principalId)
        model.assign("", true, true, context { _, _ -> error("Denied") })
        assertEquals("", model.principalDraftId)
        assertTrue(model.clearPrincipalDraft)
    }
}
