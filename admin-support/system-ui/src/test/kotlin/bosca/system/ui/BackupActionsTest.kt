package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class BackupActionsTest {
    private fun context(handler: suspend (String, JsonObject?) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables)
    })

    @Test fun `failed backup actions retain options and require fresh completed backup before restoration`() = runTest {
        val model = SystemBackupsModel(admin = true)
        model.create(true, context { _, _ -> error("Storage unavailable") })
        assertTrue(model.feedback.failed)
        assertTrue(model.includeFiles)
        assertEquals("create", model.mode)
        model.restore("backup", "OVERWRITE", false, context { _, _ -> error("Must not execute") })
        assertEquals("Confirm backup restoration.", model.feedback.message)
        assertEquals("OVERWRITE", model.strategy)
        model.restore("backup", "OVERWRITE", true, context { operation, _ ->
            assertEquals("SystemBackup", operation)
            Json.parseToJsonElement("""{"backups":{"backup":{"id":"backup","status":"failed","path":"archive.zip"}}}""")
        })
        assertTrue(model.feedback.failed)
        assertEquals("Only completed backups can be restored.", model.feedback.message)
        assertTrue(model.confirmed)
        model.delete("backup", false, context { _, _ -> error("Must not execute") })
        assertEquals("Confirm backup deletion.", model.feedback.message)
    }

    @Test fun `restore forwards selected strategy only after checking the current backup and retains it on failure`() = runTest {
        val model = SystemBackupsModel(admin = true)
        for (strategy in listOf("SKIP", "OVERWRITE", "FAIL")) {
            val calls = mutableListOf<String>()
            model.restore("backup", strategy, true, context { operation, variables ->
                calls += operation
                when (operation) {
                    "SystemBackup" -> Json.parseToJsonElement("""{"backups":{"backup":{"id":"backup","status":"completed","path":"archive.zip"}}}""")
                    "SystemRestoreBackup" -> {
                        assertEquals(strategy, variables?.get("strategy")?.jsonPrimitive?.content)
                        error("Queue unavailable")
                    }
                    else -> error("Unexpected $operation")
                }
            })
            assertEquals(listOf("SystemBackup", "SystemRestoreBackup"), calls)
            assertTrue(model.feedback.failed)
            assertEquals(strategy, model.strategy)
            assertEquals("restore", model.mode)
        }
        model.prepare("restore", "another-backup")
        assertEquals("SKIP", model.strategy)
        assertFalse(model.confirmed)
        assertFalse(model.feedback.failed)
    }

    @Test fun `offset pages retain 25 entries and use the extra row only for next page detection`() = runTest {
        val model = SystemBackupsModel(admin = true, offset = 25)
        model.reload(context { operation, variables ->
            assertEquals("SystemBackups", operation)
            assertEquals(25, variables?.get("offset")?.jsonPrimitive?.int)
            buildJsonObject { put("backups", buildJsonObject { put("records", JsonArray((1..26).map { index ->
                Json.parseToJsonElement("""{"id":"$index","status":"pending","path":null,"error":null,"includeFiles":false,"created":"2026-01-01T00:00:00Z","modified":"2026-01-01T00:00:00Z"}""")
            })) }) }
        })
        assertEquals(25, model.entries.size)
        assertEquals("25", model.entries.last().id)
        assertEquals("/system/backups?offset=0", model.previousHref)
        assertEquals("/system/backups?offset=50", model.nextHref)
    }
}
