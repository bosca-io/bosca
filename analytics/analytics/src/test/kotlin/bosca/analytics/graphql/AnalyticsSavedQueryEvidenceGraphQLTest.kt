package bosca.analytics.graphql

import bosca.analytics.model.*
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class AnalyticsSavedQueryEvidenceGraphQLTest {
    private val queries = mockk<AnalyticsQueryService>(relaxed = true)
    private val execution = mockk<AnalyticsQueryExecutionService>()
    private val grantedGroupId = UUID.random()
    private val query = AnalyticsQuery(UUID.random(), "metrics", "Metrics", "", "SELECT updated")
    private val executed = OffsetDateTime.parse("2026-10-23T12:00:00Z")
    private val evidence = AnalyticsQueryEvidence(query.id, "SELECT ? AS since, ? AS optional", listOf(
        AnalyticsQueryBinding(1, "since", QueryParameterType.DATE, JsonPrimitive("2026-10-22")),
        AnalyticsQueryBinding(2, "optional", QueryParameterType.STRING, JsonNull),
    ), executed, 17)
    private val response = AnalyticsQueryResponse(listOf(buildJsonObject { put("value", 42) }), true, executed, true, evidence)

    private fun auth(scopes: List<String>, group: String = "query-users") = object : AuthenticationContext(null, null) {
        override fun principal() = ScopedAuthenticatedPrincipal(
            Principal(UUID.random(), anonymous = false), listOf(Group(if (group == "query-users") grantedGroupId else UUID.random(), group, group, GroupType.SYSTEM)), scopes, null, 5,
        )
    }

    private val selections = "records cached stale refreshedAt evidence { queryId sql executedAt durationMs parameters { position parameter type value } }"
    private fun operation(field: String) = "{ analytics { queries { $field { $selections } } } }"

    @Test fun `query view scopes and entity grants control evidence without withholding executable rows`() = runTest {
        coEvery { queries.getPermissions(query) } returns listOf(
            Permission(grantedGroupId, PermissionAction.VIEW), Permission(grantedGroupId, PermissionAction.EXECUTE),
        )
        coEvery { queries.getQueryByKey(query.key) } returns query
        coEvery { queries.getQueryById(query.id) } returns query
        coEvery { execution.execute(query.key, emptyList()) } returns response
        coEvery { execution.execute(query.id, emptyList()) } returns response
        AnalyticsSavedQueryGraphQLFixture(queries, execution).use { fixture ->
            for (field in listOf("executeByKey(key:\"metrics\",parameters:[])", "execute(queryId:\"${query.id}\",parameters:[])")) {
                val visible = fixture.call(operation(field), auth(listOf("analytics:execute", "analytics:view")))
                assertTrue(visible.errors.isEmpty(), visible.errors.toString())
                val result = requireNotNull(visible.data).jsonObject.getValue("analytics").jsonObject.getValue("queries").jsonObject.values.single().jsonObject
                val snapshot = result.getValue("evidence").jsonObject
                assertEquals(query.id.toString(), snapshot.getValue("queryId").jsonPrimitive.content)
                assertEquals(evidence.sql, snapshot.getValue("sql").jsonPrimitive.content)
                assertEquals("17", snapshot.getValue("durationMs").jsonPrimitive.content)
                assertEquals(executed, OffsetDateTime.parse(snapshot.getValue("executedAt").jsonPrimitive.content))
                val bindings = snapshot.getValue("parameters").jsonArray
                assertEquals("DATE", bindings[0].jsonObject.getValue("type").jsonPrimitive.content)
                assertEquals("since", bindings[0].jsonObject.getValue("parameter").jsonPrimitive.content)
                assertEquals(JsonPrimitive(1), bindings[0].jsonObject.getValue("position"))
                assertEquals(JsonPrimitive("2026-10-22"), bindings[0].jsonObject.getValue("value"))
                assertEquals(JsonNull, bindings[1].jsonObject.getValue("value"))
                val hidden = fixture.call(operation(field), auth(listOf("analytics:execute")))
                assertTrue(hidden.errors.isEmpty(), hidden.errors.toString())
                val rows = requireNotNull(hidden.data).jsonObject.getValue("analytics").jsonObject.getValue("queries").jsonObject.values.single().jsonObject
                assertEquals(JsonNull, rows.getValue("evidence"))
                assertEquals(result.filterKeys { it != "evidence" }, rows.filterKeys { it != "evidence" })
                assertSame(evidence, response.evidence, "Filtering must not modify the cached response")
                coEvery { queries.getPermissions(query) } returns listOf(Permission(grantedGroupId, PermissionAction.EXECUTE))
                val executeOnlyGrant = fixture.call(operation(field), auth(listOf("analytics:execute", "analytics:view")))
                assertTrue(executeOnlyGrant.errors.isEmpty(), executeOnlyGrant.errors.toString())
                val grantedRows = requireNotNull(executeOnlyGrant.data).jsonObject.getValue("analytics").jsonObject.getValue("queries").jsonObject.values.single().jsonObject
                assertEquals(rows, grantedRows, "A VIEW scope alone does not grant access to the query definition")
                coEvery { queries.getPermissions(query) } returns listOf(
                    Permission(grantedGroupId, PermissionAction.VIEW), Permission(grantedGroupId, PermissionAction.EXECUTE),
                )

                assertTrue(fixture.call(operation(field), auth(listOf("analytics:view"))).errors.isNotEmpty())
                assertTrue(fixture.call(operation(field), auth(listOf("analytics:execute", "analytics:view"), "users")).errors.isNotEmpty())
            }
        }
    }
}
