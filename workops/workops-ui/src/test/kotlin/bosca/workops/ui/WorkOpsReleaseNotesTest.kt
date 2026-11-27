package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsReleaseNotesTest {
    private fun json(value: String) = Json.parseToJsonElement(value)
    private val variants = json("""[{"locale":"en-US","playReleaseNotes":"Play changes","appStoreWhatsNew":"Store changes","testFlightWhatToTest":"Test changes"}]""")
    private fun note(value: JsonElement = variants) = WorkOpsReleaseNoteRecord("note", "v1", "en-US", "2027-01-01T00:00:00Z", true, value).parsed()
    private fun model() = WorkOpsReleaseNotesModel("release", components = listOf(WorkOpsReleaseComponentRecord("project", "v1", projectKey = "APP", projectName = "Application", versionName = "1.0")), componentsLoaded = true, notes = listOf(note()), notesLoaded = true, canManage = true)
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun response(field: String) = json("""{"workOps":{"multiRepo":{"$field":{"id":"note","versionId":"v1"}}}}""")

    @Test fun `structured note values render actual store fields and preserve malformed values for editing`() {
        assertEquals("Play changes", note().localized.single().playReleaseNotes); assertEquals("", note().variantError)
        val malformed = note(json("""{"unexpected":"raw"}"""))
        assertTrue(malformed.localized.isEmpty()); assertTrue(malformed.variantError.isNotBlank()); assertEquals("raw", malformed.variants.jsonObject["unexpected"]?.jsonPrimitive?.content)
    }

    @Test fun `invalid and denied JSON drafts retain the captured version and full raw text`() = runTest {
        val model = model(); model.beginEdit("v1")
        model.save("[invalid", context { _, _ -> error("No invalid JSON request") })
        assertTrue(model.feedback.failed); assertEquals("[invalid", model.draft); assertEquals("v1", model.editing?.versionId)
        val raw = "  $variants\n"
        model.save(raw, context { operation, variables ->
            assertEquals("WorkOpsEditVersionNotes", operation); assertEquals(setOf("release", "version", "variants"), variables.keys)
            assertEquals("release", variables.getValue("release").jsonPrimitive.content); assertEquals("v1", variables.getValue("version").jsonPrimitive.content); assertEquals(variants, variables["variants"])
            error("Program Manage denied")
        })
        assertTrue(model.feedback.failed); assertEquals(raw, model.draft); assertEquals("APP · Application · 1.0", model.editingLabel)
        model.save("{}", context { _, _ -> error("No nonarray request") }); assertTrue(model.feedback.message.contains("array")); assertEquals("{}", model.draft)
    }

    @Test fun `refresh cannot rebase an open draft and successful save is acknowledged before failed readback`() = runTest {
        val model = model(); model.beginEdit("v1"); model.draft = "Retained invalid draft"
        model.refresh(context { _, _ -> error("Reads unavailable") })
        assertEquals("Retained invalid draft", model.draft); assertEquals("v1", model.editing?.versionId); assertEquals("APP · Application · 1.0", model.editingLabel)
        assertFalse(model.notesLoaded); assertTrue(model.readFeedback.failed); assertFalse(model.canManage)
        var writes = 0
        model.save(variants.toString(), context { operation, _ -> if (operation == "WorkOpsEditVersionNotes") { writes++; response("editVersionReleaseNotes") } else error("Readback unavailable") })
        assertEquals(1, writes); assertNull(model.editing); assertEquals("", model.draft); assertFalse(model.feedback.failed); assertEquals("Release notes saved.", model.feedback.message); assertTrue(model.readFeedback.failed)
        model.save(variants.toString(), context { _, _ -> error("Must not repeat an acknowledged write") }); assertEquals(1, writes); assertTrue(model.feedback.failed)
    }

    @Test fun `generate uses the existing aggregate mutation and reports a failed read separately`() = runTest {
        val model = model(); var writes = 0
        model.generate(context { operation, variables -> if (operation == "WorkOpsGenerateVersionNotes") {
            writes++; assertEquals(setOf("id"), variables.keys); json("""{"workOps":{"multiRepo":{"autoGenerateVersionReleaseNotes":[{"id":"note","versionId":"v1"}]}}}""")
        } else error("Readback unavailable") })
        assertEquals(1, writes); assertFalse(model.feedback.failed); assertTrue(model.readFeedback.failed); assertTrue(model.feedback.message.contains("generated"))
        model.generate(context { _, _ -> error("No linked localization project") }); assertTrue(model.feedback.failed); assertTrue(model.feedback.message.contains("localization"))
        assertFailsWith<CancellationException> { model.generate(context { _, _ -> throw CancellationException("Navigation") }) }
    }

    @Test fun `notes reload resolves labels through owning roots and removes unbundled notes without clearing their captured draft`() = runTest {
        val model = model(); model.beginEdit("v1"); val captured = model.draft
        val operations = mutableListOf<String>()
        model.refresh(context { operation, _ -> operations += operation; when (operation) {
            "WorkOpsReleaseComponents" -> json("""{"workOps":{"crossProject":{"versionsForRelease":[]}}}""")
            "WorkOpsReleaseVersionNotes" -> json("""{"workOps":{"multiRepo":{"versionReleaseNotes":[]}}}""")
            "WorkOpsReleaseAccess" -> json("""{"workOps":{"crossProject":{"releaseActionAccess":{"canManage":false}}}}""")
            else -> error(operation)
        } })
        assertTrue(model.notesLoaded); assertTrue(model.notes.isEmpty()); assertEquals(captured, model.draft); assertEquals("v1", model.editing?.versionId)
        assertEquals(listOf("WorkOpsReleaseComponents", "WorkOpsReleaseVersionNotes", "WorkOpsReleaseAccess"), operations)
        model.cancel(); assertNull(model.editing); assertEquals("", model.draft)
    }
}
