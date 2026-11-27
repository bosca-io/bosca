package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsSpecContextsTest {
    private val target = "10000000-0000-0000-0000-000000000001"
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun contexts(rows: String = "") = Json.parseToJsonElement("""{"workOps":{"specs":{"spec":{"contexts":[$rows]}}}}""")
    private fun row(kind: String, id: String = target, label: String? = null) = """{"id":"context-$kind","contextType":"$kind","targetId":${JsonPrimitive(id)},"label":${label?.let(::JsonPrimitive) ?: JsonNull},"createdAt":"2026-11-14T10:00:00Z"}"""

    @Test fun `all authorized context kinds resolve their own names and missing references retain navigation`() = runTest {
        val kinds = listOf("METADATA", "COLLECTION", "PROFILE", "SPEC", "TASK", "PROJECT")
        for (available in listOf(true, false)) {
            fun value(present: String) = if (available) present else "null"
            val model = WorkOpsSpecContextsModel("spec", "/workops/specs/spec")
            model.retry(context { operation, _ -> when (operation) {
                "WorkOpsSpecContexts" -> contexts(kinds.joinToString(",") { row(it, label = " ") })
                "WorkOpsRequirementName" -> Json.parseToJsonElement("""{"content":{"metadata":${value("{\"name\":\"Document\"}")}}}""")
                "WorkOpsContextCollection" -> Json.parseToJsonElement("""{"content":{"collections":{"collection":${value("{\"name\":\"Collection\"}")}}}}""")
                "WorkOpsTaskProfile" -> Json.parseToJsonElement("""{"profiles":{"profile":${value("{\"id\":\"$target\",\"name\":\"Person\"}")}}}""")
                "WorkOpsSpecChoice" -> Json.parseToJsonElement("""{"workOps":{"specs":{"spec":${value("{\"id\":\"$target\",\"metadataId\":\"metadata\",\"metadata\":{\"name\":\"Specification\"}}")}}}}""")
                "WorkOpsRequirementParentTask" -> Json.parseToJsonElement("""{"workOps":{"tasks":{"task":${value("{\"summary\":\"Task\"}")}}}}""")
                "WorkOpsContextProject" -> Json.parseToJsonElement("""{"workOps":{"projects":{"project":${value("{\"name\":\"Project\"}")}}}}""")
                else -> error(operation)
            } })
            assertFalse(model.feedback.failed, model.feedback.message)
            assertEquals(if (available) listOf("Document", "Collection", "Person", "Specification", "Task", "Project") else List(6) { "Reference unavailable" }, model.rows.map { it.title })
            assertTrue(model.rows.all { it.href.startsWith("/workops/") })
        }
    }

    @Test fun `invalid identifiers and unsupported external URI shapes never trigger entity lookups`() = runTest {
        val model = WorkOpsSpecContextsModel("spec", "/workops/specs/spec")
        model.retry(context { operation, _ ->
            assertEquals("WorkOpsSpecContexts", operation)
            contexts(listOf(row("METADATA", "invalid-id"), row("EXTERNAL_URI", "https://["), row("EXTERNAL_URI", "https://"),
                row("EXTERNAL_URI", "https://example.test/document"), row("CHAT_CHANNEL", "channel"), row("AI_SESSION", "session")).joinToString(","))
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals("Reference unavailable", model.rows[0].title)
        assertTrue(model.rows.take(3).all { it.href.isEmpty() })
        assertEquals("https://example.test/document", model.rows[3].href)
        assertEquals(listOf("Chat channel", "AI session"), model.rows.takeLast(2).map { it.kind })
    }

    @Test fun `contexts retain authored labels and reference origins without exposing inaccessible specification names`() = runTest {
        val origin = "/workops/specs/current?tab=history&offset=25"
        val model = WorkOpsSpecContextsModel("spec", origin)
        model.reload(context { operation, _ -> when (operation) {
            "WorkOpsSpecContexts" -> contexts(listOf(row("METADATA"), row("SPEC"), row("PROFILE", label = "Our CRM record"),
                row("EXTERNAL_URI", "javascript:alert(1)"), row("GIT_RESOURCE", "repository/path")).joinToString(","))
            "WorkOpsRequirementName" -> Json.parseToJsonElement("""{"content":{"metadata":{"name":"Report document"}}}""")
            "WorkOpsSpecChoice" -> Json.parseToJsonElement("""{"workOps":{"specs":{"spec":null}}}""")
            "WorkOpsTaskProfile" -> Json.parseToJsonElement("""{"profiles":{"profile":null}}""")
            else -> error(operation)
        } })
        assertEquals("Report document", model.rows[0].title)
        assertEquals(workOpsReferenceHref("metadata", target, origin), model.rows[0].href)
        assertEquals("Reference unavailable", model.rows[1].title)
        assertEquals("Our CRM record", model.rows[2].title)
        assertEquals(workOpsReferenceHref("profile", target, origin), model.rows[2].href)
        assertEquals("", model.rows[3].href); assertEquals("repository/path", model.rows[4].title)
    }

    @Test fun `context type changes retain labels and rejected references retain the selected title and draft`() = runTest {
        val model = WorkOpsSpecContextsModel("spec", "/workops/specs/spec").apply { open() }
        model.chooseType("SPEC", "https://example.test", "Dependency")
        assertEquals("", model.targetDraft); assertEquals("Dependency", model.labelDraft)
        model.add(target, "Dependency", context { operation, variables -> when (operation) {
            "WorkOpsSpecChoice" -> Json.parseToJsonElement("""{"workOps":{"specs":{"spec":{"id":"$target","metadataId":"metadata","metadata":{"name":"Referenced specification"}}}}}""")
            "WorkOpsAddSpecContext" -> {
                val input = variables.getValue("input").jsonObject
                assertEquals("SPEC", input.getValue("contextType").jsonPrimitive.content)
                assertEquals(target, input.getValue("targetId").jsonPrimitive.content)
                assertEquals("Dependency", input.getValue("label").jsonPrimitive.content)
                error("Context rejected")
            }
            else -> error(operation)
        } })
        assertTrue(model.feedback.failed); assertTrue(model.adding); assertEquals(target, model.targetDraft)
        assertEquals("Referenced specification", model.targetName); assertEquals("Dependency", model.labelDraft)
        model.add(target, "Dependency", context { operation, _ ->
            assertEquals("WorkOpsSpecChoice", operation); Json.parseToJsonElement("""{"workOps":{"specs":{"spec":null}}}""")
        })
        assertEquals("The reference is unavailable. Choose another reference.", model.feedback.message)
    }

    @Test fun `external contexts reject active or incomplete links and remove only current context rows`() = runTest {
        val model = WorkOpsSpecContextsModel("spec", "/workops/specs/spec").apply { open() }
        for (target in listOf("javascript:alert(1)", "/relative", "https:")) {
            model.add(target, "Reference", context { operation, _ -> error("Unexpected $operation") })
            assertEquals("Enter a full HTTP or HTTPS link.", model.feedback.message)
        }
        model.add("https://example.test/reference", "Reference", context { operation, _ -> when (operation) {
            "WorkOpsAddSpecContext" -> Json.parseToJsonElement("""{"workOps":{"specs":{"addContext":{"id":"created"}}}}""")
            "WorkOpsSpecContexts" -> contexts(row("EXTERNAL_URI", "https://example.test/reference"))
            else -> error(operation)
        } })
        assertFalse(model.feedback.failed); assertFalse(model.adding); assertEquals("", model.targetDraft)
        model.remove("unknown", context { operation, _ -> error("Unexpected $operation") }); assertTrue(model.feedback.failed)
        model.remove("context-EXTERNAL_URI", context { operation, _ -> when (operation) {
            "WorkOpsRemoveSpecContext" -> Json.parseToJsonElement("""{"workOps":{"specs":{"removeContext":true}}}""")
            "WorkOpsSpecContexts" -> contexts()
            else -> error(operation)
        } })
        assertFalse(model.feedback.failed); assertTrue(model.rows.isEmpty())
        assertFailsWith<CancellationException> { model.retry(context { _, _ -> throw CancellationException("closed") }) }
    }
}
