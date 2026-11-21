package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsComponentSettingsTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: buildJsonObject {})
    })
    @Test fun `settings reuse component reads and retain stored ownership identities and scoped offset links`() = runTest {
        val model = WorkOpsProjectCatalogModel("project", "components", targetId = "component-26", settings = true)
        model.retry(context { operation, variables ->
            assertEquals("WorkOpsProjectCatalogs", operation); assertEquals("project", variables.getValue("id").jsonPrimitive.content)
            buildJsonObject { putJsonObject("workOps") {
                putJsonObject("versions") { putJsonArray("byProject") {} }; putJsonObject("labels") { putJsonArray("byProject") {} }
                putJsonObject("components") { putJsonArray("byProject") { repeat(28) { n -> add(buildJsonObject { put("id", "component-$n"); put("name", "Component $n"); put("description", JsonNull); put("assigneeMode", "COMPONENT_LEAD_OR_PROJECT_DEFAULT"); put("leadProfileId", "private-lead"); put("defaultAssigneeProfileId", "private-assignee"); put("version", 7) }) } } }
            } }
        })
        assertTrue(model.loaded); assertEquals(25L, model.offset); assertEquals(3, model.pageRows.size)
        assertEquals("private-lead", model.rows.first().leadProfileId); assertEquals("private-assignee", model.rows.first().defaultAssigneeProfileId)
        assertEquals("/workops/settings/components?projectId=project&offset=0", model.previousHref)
        model.resultId = "component-26"; assertEquals("/workops/settings/components?projectId=project&offset=0&item=component-26#workops-project-components-component-26", model.resultHref)
        model.beginDelete("component-26"); assertEquals("private-lead", model.deleteTarget?.leadProfileId)
    }

    @Test fun `settings mode cannot reinterpret another project catalog`() = runTest {
        for (kind in listOf("versions", "labels")) {
            val model = WorkOpsProjectCatalogModel("project", kind, settings = true)
            model.retry(context { _, _ -> error("Must not read") })
            assertFalse(model.loaded); assertTrue(model.feedback.failed); assertEquals("Choose the component settings catalog.", model.feedback.message)
        }
    }
}
