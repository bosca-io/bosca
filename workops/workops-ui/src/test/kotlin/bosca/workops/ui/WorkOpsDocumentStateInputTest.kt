package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.decodeClientState
import bosca.bml.render.encodeState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsDocumentStateInputTest {
    private val noWrites = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement =
            throw AssertionError("Incomplete document state attempted $operationName")
    })

    @Test fun `a task form without its definition retains custom values without creating a task`() = runTest {
        val model = decodeClientState<WorkOpsTaskCreateModel>("""{"open":true,"projectId":"project"}""")
        model.submit("""{"summary":"Unsaved task","projectId":"project","custom":"retained"}""", noWrites)
        val returned = decodeClientState<WorkOpsTaskCreateModel>(encodeState(model))
        assertEquals("Load the task form before creating a task.", returned.error)
        assertTrue(returned.open)
        assertEquals(JsonPrimitive("Unsaved task"), returned.draft["summary"])
        assertEquals(JsonPrimitive("retained"), returned.draft["custom"])
        assertEquals("", returned.createdId)
    }

    @Test fun `missing specification project choices retain title and parent without creating a document`() = runTest {
        val model = decodeClientState<WorkOpsSpecCreateModel>("""{"creating":true}""")
        model.create("Unsaved specification", "project", "parent", noWrites)
        val returned = decodeClientState<WorkOpsSpecCreateModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Choose an available active project.", returned.feedback.message)
        assertEquals("Unsaved specification", returned.nameDraft)
        assertEquals("project", returned.projectId)
        assertEquals("parent", returned.parentId)
        assertEquals("", returned.createdId)
    }

    @Test fun `an unopened requirement form retains its title and parent kind without creating a linked task`() = runTest {
        val model = decodeClientState<WorkOpsRequirementsModel>("""{"parentId":"spec","parentHref":"/workops/specs/spec","parentType":"SPEC"}""")
        model.create("Unsaved requirement", noWrites)
        val returned = decodeClientState<WorkOpsRequirementsModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Enter a requirement name.", returned.feedback.message)
        assertEquals("Unsaved requirement", returned.nameDraft)
        assertEquals("spec", returned.parentId)
        assertEquals("SPEC", returned.parentType.name)
    }

    @Test fun `an unopened context form retains the entered reference without adding a context`() = runTest {
        val model = decodeClientState<WorkOpsSpecContextsModel>("""{"specId":"spec","returnTo":"/workops/specs/spec"}""")
        model.add("https://example.test/reference", "Unsaved reference", noWrites)
        val returned = decodeClientState<WorkOpsSpecContextsModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Choose or enter a reference.", returned.feedback.message)
        assertEquals("https://example.test/reference", returned.targetDraft)
        assertEquals("Unsaved reference", returned.labelDraft)
    }

    @Test fun `missing Git binding retains explicit human author details without pushing`() = runTest {
        val model = decodeClientState<WorkOpsSpecGitModel>("""{"specId":"spec","pushing":true}""")
        model.push("Human Author", "human@example.test", noWrites)
        val returned = decodeClientState<WorkOpsSpecGitModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Open a linked specification before pushing.", returned.feedback.message)
        assertEquals("Human Author", returned.authorNameDraft)
        assertEquals("human@example.test", returned.authorEmailDraft)
        assertEquals("", returned.commitSha)
    }

    @Test fun `unavailable Content state cannot acknowledge a document save revision`() = runTest {
        val model = decodeClientState<WorkOpsDocumentModel>("""{"metadataId":"document"}""")
        model.save("""{"type":"doc","content":[]}""", "{}", noWrites)
        val returned = decodeClientState<WorkOpsDocumentModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Reload the document before saving.", returned.feedback.message)
        assertEquals("", returned.savedRevision)
        assertFalse(returned.canEdit)
        assertEquals("doc", returned.body.getValue("type").jsonPrimitive.content)
    }

    @Test fun `unloaded notification preferences retain hidden mutes and cannot overwrite stored preferences`() = runTest {
        val model = decodeClientState<WorkOpsInboxModel>("""{"tab":"preferences","draft":{"mutedTaskIds":["task"],"mutedProjectIds":["project"]}}""")
        model.savePreferences("{}", noWrites)
        val returned = decodeClientState<WorkOpsInboxModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Load notification preferences before saving.", returned.feedback.message)
        assertEquals(listOf("task"), returned.draft.mutedTaskIds)
        assertEquals(listOf("project"), returned.draft.mutedProjectIds)
        assertFalse(returned.preferencesLoaded)
    }

    @Test fun `a deleted specification cannot generate tasks and retains the requested snapshot version`() = runTest {
        val model = decodeClientState<WorkOpsSpecGenerationsModel>("""{"specId":"spec","returnTo":"/workops/specs/spec"}""")
        val unavailable = RenderContext(gql = object : GraphQLClient {
            override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
                assertEquals("WorkOpsSpecGenerationVersion", operationName)
                return Json.parseToJsonElement("""{"workOps":{"specs":{"spec":null}}}""")
            }
        })
        model.generate("7", unavailable)
        val returned = decodeClientState<WorkOpsSpecGenerationsModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("The specification is unavailable.", returned.feedback.message)
        assertEquals("7", returned.versionDraft)
    }
}
