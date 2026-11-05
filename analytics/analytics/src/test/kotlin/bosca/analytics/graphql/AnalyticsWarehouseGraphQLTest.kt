package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQueryColumn
import bosca.analytics.model.AnalyticsSqlResult
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class AnalyticsWarehouseGraphQLTest {
    private val execution = mockk<AnalyticsQueryExecutionService>()
    private fun auth(scope: String, group: String = "administrators", restrictGroups: Boolean = false) = object : AuthenticationContext(null, null) {
        override fun principal() = ScopedAuthenticatedPrincipal(
            Principal(UUID.random(), anonymous = false), listOf(Group(UUID.random(), group, group, GroupType.SYSTEM)), listOf(scope), if (restrictGroups) emptySet() else null, 5,
        )
    }
    private val query = """{ analytics { warehouse {
        catalogs schemas(catalog:"warehouse") tables(catalog:"warehouse",schema:"bosca")
        describe(catalog:"warehouse",schema:"bosca",table:"events") { name typeName nullable }
        execute(sql:"SELECT 42 AS value") { sql records columns { name } truncated executedAt durationMs }
    } } }"""

    @Test fun `warehouse scope and role are independently required for every entry point`() = runTest {
        AnalyticsWarehouseGraphQLFixture(execution).use { fixture ->
            for (caller in listOf(auth("analytics:execute"), auth("analytics:view"), auth("analytics:manage"), auth("analytics:sql", "users"), auth("analytics:sql", restrictGroups = true), AuthenticationContext(null, null))) {
                for (field in listOf("catalogs", "schemas(catalog:\"warehouse\")", "tables(catalog:\"warehouse\",schema:\"bosca\")",
                    "describe(catalog:\"warehouse\",schema:\"bosca\",table:\"events\") { name }", "execute(sql:\"SELECT 1\") { sql }")) {
                    assertTrue(fixture.call("{ analytics { warehouse { $field } } }", caller).errors.isNotEmpty(), field)
                }
            }
            coVerify(exactly = 0) { execution.listCatalogs(any(), any()); execution.listSchemas(any(), any(), any()); execution.listTables(any(), any(), any(), any()); execution.describeTable(any(), any(), any()); execution.executeSql(any(), any()) }
        }
    }

    @Test fun `administrator and analytics manager get typed execution evidence with SDL defaults`() = runTest {
        val column = AnalyticsQueryColumn("value", "bigint", false)
        coEvery { execution.listCatalogs(0, 100) } returns listOf("warehouse")
        coEvery { execution.listSchemas("warehouse", 0, 100) } returns listOf("bosca")
        coEvery { execution.listTables("warehouse", "bosca", 0, 100) } returns listOf("events")
        coEvery { execution.describeTable("warehouse", "bosca", "events") } returns listOf(column)
        coEvery { execution.executeSql("SELECT 42 AS value", 100) } returns AnalyticsSqlResult("SELECT 42 AS value", listOf(column), listOf(buildJsonObject { put("value", 42) }), true, OffsetDateTime.now(), 12)
        AnalyticsWarehouseGraphQLFixture(execution).use { fixture ->
            for (group in listOf("administrators", "analytics.manager")) {
                val result = fixture.call(query, auth("analytics:sql", group))
                assertTrue(result.errors.isEmpty(), result.errors.toString())
                assertContains(result.data.toString(), "SELECT 42 AS value")
                assertContains(result.data.toString(), "\"durationMs\":12")
                assertContains(result.data.toString(), "\"truncated\":true")
            }
            coEvery { execution.listCatalogs(20, 10) } returns emptyList()
            assertTrue(fixture.call("{ analytics { warehouse { catalogs(offset:20,limit:10) } } }", auth("analytics:sql")).errors.isEmpty())
            coVerify { execution.listCatalogs(20, 10) }
        }
    }
}
