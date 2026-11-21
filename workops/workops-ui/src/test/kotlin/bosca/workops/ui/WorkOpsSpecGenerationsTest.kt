package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsSpecGenerationsTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun generations(count: Int = 0) = buildJsonObject {
        putJsonObject("workOps") { putJsonObject("specs") { putJsonObject("spec") {
            putJsonObject("metadata") { put("version", 4) }
            putJsonArray("taskGenerations") { repeat(count) { index -> addJsonObject {
                put("id", "generation-$index"); put("metadataVersion", 3); put("source", "MANUAL"); put("createdAt", "2026-11-14T10:00:00Z")
                putJsonArray("generatedTaskIds") { add(JsonPrimitive("visible")); add(JsonPrimitive("private")) }
            } } }
        } } }
    }

    @Test fun `generation pages use a sentinel and resolve visible task names once without exposing tracking keys`() = runTest {
        val model = WorkOpsSpecGenerationsModel("spec", "/workops/specs/spec?tab=history&offset=25&childrenOffset=25&requirementsOffset=25", 25)
        val resolved = mutableListOf<String>()
        model.reload(context { operation, variables -> when (operation) {
            "WorkOpsSpecGenerations" -> {
                assertEquals(25L, variables.getValue("offset").jsonPrimitive.long); generations(26)
            }
            "WorkOpsRequirementParentTask" -> {
                val id = variables.getValue("id").jsonPrimitive.content; resolved += id
                if (id == "private") Json.parseToJsonElement("""{"workOps":{"tasks":{"task":null}}}""")
                else Json.parseToJsonElement("""{"workOps":{"tasks":{"task":{"summary":"REQ TEAM-REQ-4"}}}}""")
            }
            else -> error(operation)
        } })
        assertEquals(25, model.rows.size); assertEquals(listOf("visible", "private"), resolved)
        assertEquals("Requirement", model.taskName("visible")); assertEquals("Task unavailable", model.taskName("private"))
        assertEquals("4", model.versionDraft)
        assertTrue(model.nextHref.endsWith("requirementsOffset=25&generationsOffset=50"))
        assertTrue(model.previousHref.endsWith("requirementsOffset=25&generationsOffset=0"))
    }

    @Test fun `generation refreshes visible Content version instead of submitting a stale or zero version`() = runTest {
        val model = WorkOpsSpecGenerationsModel("spec", "/workops/specs/spec")
        model.generate("1", context { operation, variables -> when (operation) {
            "WorkOpsSpecGenerationVersion" -> Json.parseToJsonElement("""{"workOps":{"specs":{"spec":{"metadata":{"version":4}}}}}""")
            "WorkOpsGenerateSpecTasks" -> {
                assertEquals("spec", variables.getValue("id").jsonPrimitive.content)
                assertEquals(4, variables.getValue("version").jsonPrimitive.int)
                Json.parseToJsonElement("""{"workOps":{"specs":{"generateTasks":{"id":"generation","generatedTaskIds":["one","two"]}}}}""")
            }
            "WorkOpsSpecGenerations" -> generations()
            else -> error(operation)
        } })
        assertFalse(model.feedback.failed); assertEquals("4", model.versionDraft); assertEquals("Generated 2 tasks.", model.feedback.message)
    }

    @Test fun `WorkOps edit without Content view preserves explicit version entry and failed generation drafts`() = runTest {
        val model = WorkOpsSpecGenerationsModel("spec", "/workops/specs/spec")
        val ctx = context { operation, variables -> when (operation) {
            "WorkOpsSpecGenerationVersion" -> Json.parseToJsonElement("""{"workOps":{"specs":{"spec":{"metadata":null}}}}""")
            "WorkOpsGenerateSpecTasks" -> {
                assertEquals(3, variables.getValue("version").jsonPrimitive.int); error("Generation rejected")
            }
            else -> error(operation)
        } }
        model.generate("0", ctx)
        assertEquals("Enter the document version to record with this generation.", model.feedback.message)
        model.generate("3", ctx); assertTrue(model.feedback.failed); assertEquals("3", model.versionDraft)
        assertFailsWith<CancellationException> { model.retry(context { _, _ -> throw CancellationException("closed") }) }
    }
}
