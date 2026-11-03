package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.system.ui.graphql.SystemPackages
import bosca.system.ui.graphql.SystemDefinitionsData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SystemActionsTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    @Test fun `unconfirmed destructive actions never call the API`() = runTest {
        val model = SystemPage("groups", true)
        model.action("group", "delete", false, context { _, _ -> error("Must not execute") })
        assertTrue(model.feedback.failed)
    }
    @Test fun `cancelling history sends the history ID to the scheduler`() = runTest {
        val model = SystemPage("jobs", true)
        val operations = mutableListOf<String>()
        model.action("history-id", "cancel", true, context { operation, variables ->
            operations += operation
            when (operation) {
                "SystemCancelHistory" -> {
                    assertEquals("history-id", variables.getValue("id").jsonPrimitive.content)
                    Json.parseToJsonElement("""{"scheduler":{"cancel":{"id":"history-id"}}}""")
                }
                "SystemHistory" -> Json.parseToJsonElement("""{"scheduler":{"history":[],"historyCount":0}}""")
                else -> error(operation)
            }
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals(listOf("SystemCancelHistory", "SystemHistory"), operations)
    }
    @Test fun `server cancellation propagates through feedback`() = runTest {
        val model = SystemPage("groups", true)
        assertFailsWith<CancellationException> {
            model.createGroup("New group", "", "PRINCIPAL", context { _, _ -> throw CancellationException() })
        }
    }
    @Test fun `history counts describe the current filtered page and retain full execution fields`() = runTest {
        val model = SystemPage("jobs", true, offset = 25, status = "RUNNING", source = "EVENT")
        model.reload(context { operation, variables ->
            assertEquals("SystemHistory", operation)
            assertEquals(25, variables.getValue("offset").jsonPrimitive.int)
            assertEquals("RUNNING", variables.getValue("status").jsonPrimitive.content)
            assertEquals("EVENT", variables.getValue("source").jsonPrimitive.content)
            Json.parseToJsonElement("""{"scheduler":{"history":[{"id":"history","jobId":"job","name":"Import","status":"RUNNING","source":"EVENT","triggeredAt":"2026-11-02T00:00:00Z","scheduledFor":"2026-11-02T00:00:00Z","completedAt":null,"errorMessage":"Full error\nwith detail","wasCatchUp":true,"delayedUntil":"2026-11-03T00:00:00Z","parentJobId":"parent"}],"historyCount":27}}""")
        })
        assertEquals("27 total · 1 running · 0 pending", model.historySummary)
        assertEquals("Full error\nwith detail", model.history.single().errorMessage)
        assertEquals("parent", model.history.single().parentJobId)
        assertTrue(model.hasNext)
    }
    @Test fun `schedule list keeps description cron and run times in separate fields`() = runTest {
        val model = SystemPage("scheduler", true)
        model.reload(context { operation, _ ->
            assertEquals("SystemSchedules", operation)
            Json.parseToJsonElement("""{"scheduler":{"jobs":[{"id":"scheduled","name":"Nightly","description":"Index content","cronExpression":"0 2 * * *","enabled":true,"lastRunAt":"2026-11-02T02:00:00Z","nextRunAt":"2026-11-03T02:00:00Z"},{"id":"manual","name":"Manual","description":null,"cronExpression":null,"enabled":false,"lastRunAt":null,"nextRunAt":null}]}}""")
        })
        val scheduled = model.rows.first()
        assertEquals("Index content", scheduled.detail)
        assertEquals("0 2 * * *", scheduled.cron)
        assertEquals("2026-11-02T02:00:00Z", scheduled.lastRun)
        assertEquals("2026-11-03T02:00:00Z", scheduled.nextRun)
        assertNull(model.rows.last().cron)
        assertNull(model.rows.last().nextRun)
    }
    @Test fun `package summaries use installed installer versions rather than historical attempts`() {
        val data = SystemPackages.decodeData(Json.parseToJsonElement("""{"packages":{"all":[{"key":"package","name":"Package","versions":[{"version":"1","installers":[{"name":"a","version":"2"}],"installed":[{"name":"a","version":"1"}]},{"version":"2","installers":[{"name":"a","version":"2"}],"installed":[{"name":"a","version":"2"}]},{"version":"3","installers":[],"installed":[]}]}],"history":[{"id":"history","key":"package","version":"1","created":"2026-11-02T00:00:00Z"}]}}"""))
        val model = SystemPackageModel(true, data)
        assertEquals(3, model.totalVersions)
        assertEquals(1, model.installedVersions)
        assertFalse(model.versionInstalled(data.packages.all.single().versions.first()))
        assertFalse(model.versionInstalled(data.packages.all.single().versions.last()))
    }
    @Test fun `already installed response succeeds only when authoritative status confirms the installer`() = runTest {
        val model = SystemPackageModel(true)
        for (present in listOf(true, false)) {
            model.install("package", "1", "a", "2", true, context { operation, _ ->
                if (operation == "SystemInstallInstaller") Json.parseToJsonElement("""{"packages":{"installInstaller":false}}""")
                else Json.parseToJsonElement("""{"packages":{"all":[{"key":"package","name":"Package","versions":[{"version":"1","installers":[{"name":"a","version":"2"}],"installed":${if (present) """[{"name":"a","version":"2"}]""" else "[]"}}]}],"history":[]}}""")
            })
            assertEquals(!present, model.feedback.failed, model.feedback.message)
        }
    }
    @Test fun `invalid cron preserves the draft and never saves a schedule`() = runTest {
        val model = SystemScheduleModel("new", true, definitions = listOf(SystemDefinitionsData.Scheduler.AvailableJobDefinitions("job", "job", "Job", "common", null)))
        model.save("Name", "Description", "job", "{}", "bad cron", true, false, false, "1", context { operation, _ ->
            assertEquals("SystemCron", operation)
            Json.parseToJsonElement("""{"scheduler":{"validateCronExpression":{"valid":false,"error":"Invalid expression","nextRuns":[]}}}""")
        })
        assertTrue(model.feedback.failed)
        assertEquals("Invalid expression", model.feedback.message)
        assertEquals("bad cron", model.cron)
        assertEquals("Name", model.name)
    }
}
