package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.system.ui.generated.SystemPostgresComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class PostgresActionsTest {
    private fun fixture(name: String) = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/postgres/$name.json")).bufferedReader().use { it.readText() })
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun result(field: String, value: JsonElement) = buildJsonObject { put("postgresAdmin", buildJsonObject { put(field, value) }) }
    private fun tableResult(field: String = "tableStats") = result(field, JsonArray(listOf(fixture("table"))))
    private val index = Json.parseToJsonElement("""{"schemaName":"public","tableName":"system_ui_console_probe","indexName":"probe_pkey","idxScan":2,"idxTupRead":3,"idxTupFetch":3,"indexSize":4096,"indexDef":"CREATE UNIQUE INDEX probe_pkey ON public.system_ui_console_probe USING btree (id)"}""")

    @Test fun `tab loading is lazy filters persist and index queries stay sequential`() = runTest {
        val calls = mutableListOf<String>()
        val ctx = context { operation, variables ->
            calls += operation
            when (operation) {
                "SystemGetPostgresTableStats" -> tableResult()
                "SystemGetPostgresIndexStats" -> { assertEquals("public", variables["schemaName"]?.jsonPrimitive?.content); result("indexStats", JsonArray(listOf(index))) }
                "SystemGetPostgresUnusedIndexes" -> result("unusedIndexes", JsonArray(listOf(index)))
                "SystemGetPostgresIndexSuggestions" -> result("indexSuggestions", JsonArray(emptyList()))
                else -> fixture("overview")
            }
        }
        val model = PostgresConsoleModel(true, indexSchema = "public")
        model.chooseTab("Tables", ctx); assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals(listOf("SystemGetPostgresTableStats"), calls)
        model.chooseTab("Overview", ctx); model.chooseTab("Tables", ctx); assertEquals(1, calls.size)
        model.chooseTab("Indexes", ctx)
        assertEquals(listOf("SystemGetPostgresIndexStats", "SystemGetPostgresUnusedIndexes", "SystemGetPostgresIndexSuggestions"), calls.takeLast(3))
        model.chooseIndexTab("unused"); assertEquals("probe_pkey", model.shownIndexes.single().indexName)
        model.openIndex("public", "probe_pkey"); val request = model.indexOpenRequest
        assertTrue(model.selectedIndex?.indexDef?.startsWith("CREATE UNIQUE") == true)
        model.openIndex("public", "probe_pkey"); assertNotEquals(request, model.indexOpenRequest)
    }

    @Test fun `overview refresh retains tables filters details and index tab`() = runTest {
        val ctx = context { operation, _ -> if (operation == "SystemGetPostgresTableStats") tableResult() else fixture("overview") }
        val model = PostgresConsoleModel(true)
        model.loadTables("public", ctx); model.openTable("public", "system_ui_console_probe")
        val request = model.tableOpenRequest
        model.tab = "Tables"; model.indexTab = "unused"; model.refresh(ctx)
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals("public", model.tableSchema); assertEquals("Tables", model.tab); assertEquals("unused", model.indexTab)
        assertEquals(1, model.tables.size); assertEquals(request, model.tableOpenRequest); assertEquals(1L, model.refreshes)
        assertEquals(12288L, model.selectedTable?.totalSize)
    }

    @Test fun `bloat and IO filters retain empty results and reject invalid drafts before IO`() = runTest {
        val model = PostgresConsoleModel(true)
        model.loadBloated("NaN", context { _, _ -> error("Must not query") })
        assertTrue(model.feedback.failed); assertEquals("NaN", model.minBloat)
        model.loadBloated("5", context { operation, variables ->
            assertEquals("SystemGetPostgresBloatedTables", operation); assertEquals(5.0, variables.getValue("minBloatPercent").jsonPrimitive.double)
            result("bloatedTables", JsonArray(emptyList()))
        }); assertTrue(model.bloatedLoaded); assertFalse(model.feedback.failed)
        model.loadIO("public", context { operation, variables ->
            assertEquals("SystemGetPostgresTableIOStats", operation); assertEquals("public", variables.getValue("schemaName").jsonPrimitive.content)
            result("tableIOStats", JsonArray(emptyList()))
        }); assertTrue(model.ioLoaded); assertEquals("public", model.tableSchema)
        model.loadQueries("0", "total", context { _, _ -> error("Must not query") }); assertTrue(model.feedback.failed); assertEquals("0", model.queryLimit)
        model.loadQueries("30", "unknown", context { _, _ -> error("Must not query") }); assertTrue(model.feedback.failed)
    }

    @Test fun `false maintenance results remain failures and unconfirmed operations never execute`() = runTest {
        val model = PostgresConsoleModel(true)
        val unused = context { _, _ -> error("Must not query") }
        model.cancelQuery("501", false, unused); assertTrue(model.feedback.failed)
        model.terminateBackend("501", false, unused); assertTrue(model.feedback.failed)
        model.analyzeTable("public", "probe", false, unused); assertTrue(model.feedback.failed)
        model.resetStatements(false, unused); assertTrue(model.feedback.failed)
        model.cancelQuery("501", true, context { _, _ -> result("cancelQuery", JsonPrimitive(false)) })
        assertTrue(model.feedback.failed); assertEquals("cancel:501", model.failedOperation); assertEquals("", model.completedOperation)
        model.cancelQuery("501", true, context { operation, _ -> if (operation == "SystemCancelPgQuery") result("cancelQuery", JsonPrimitive(true)) else fixture("overview") })
        assertFalse(model.feedback.failed, model.feedback.message); assertEquals("cancel:501", model.completedOperation); assertEquals(1L, model.refreshes)
    }

    @Test fun `completed analyze stays completed when the subsequent table reload fails`() = runTest {
        val model = PostgresConsoleModel(true)
        model.analyzeTable("public", "probe", true, context { operation, variables ->
            if (operation == "SystemAnalyzePgTable") {
                assertEquals("public", variables.getValue("schemaName").jsonPrimitive.content)
                assertEquals("probe", variables.getValue("tableName").jsonPrimitive.content)
                result("analyzeTable", JsonPrimitive(true))
            } else error("Reload failed")
        })
        assertTrue(model.feedback.failed); assertEquals("Reload failed", model.feedback.message)
        assertEquals("analyze:public.probe", model.completedOperation); assertEquals("", model.failedOperation)
    }

    @Test fun `authorization cancellation and diagnostic errors preserve their contracts`() = runTest {
        val model = PostgresConsoleModel(false)
        model.chooseTab("Tables", context { _, _ -> error("Must not query") }); assertTrue(model.feedback.failed)
        model.refresh(context { _, _ -> error("Must not query") }); assertTrue(model.feedback.failed)
        val admin = PostgresConsoleModel(true)
        assertFailsWith<CancellationException> { admin.refresh(context { _, _ -> throw CancellationException() }) }
        admin.loadTables("public", context { _, _ -> error("Database unavailable") })
        assertEquals("Database unavailable", admin.feedback.message); assertEquals("public", admin.tableSchema)
    }

    @Test fun `compiled views expose cluster maintenance query and table diagnostics`() = runTest {
        val model = PostgresConsoleModel(true)
        model.refresh(context { _, _ -> fixture("overview") })
        model.loadTables("public", context { _, _ -> tableResult() }); model.openTable("public", "system_ui_console_probe")
        model.loadBloated("0", context { _, _ -> tableResult("bloatedTables") })
        val ctx = RenderContext(); SystemPostgresComponent.render(ctx, mapOf("initial" to model)) {}
        val html = ctx.writer.toString()
        for (text in listOf("Fixture pool", "Fixture replica", "fixture_slot", "scanning heap", "Servers logging in", "Average transactions/s", "Last autoanalyze", "HOT updated", "Run ANALYZE", "pg-panel-Settings", "pg-panel-Locks")) assertTrue(text in html, text)
        assertTrue("select pg_sleep(30)" in html)
        assertTrue("data-pg-active-queries" in html)
        assertTrue(Regex("<span[^>]*data-resource-name[^>]*>public.system_ui_console_probe</span>").containsMatchIn(html))
        assertFalse(Regex("<button[^>]*>public.system_ui_console_probe</button>").containsMatchIn(html))
    }
}
