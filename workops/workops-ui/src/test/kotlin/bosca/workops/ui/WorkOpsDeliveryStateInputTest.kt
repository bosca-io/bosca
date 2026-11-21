package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.decodeClientState
import bosca.bml.render.encodeState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsDeliveryStateInputTest {
    private val noWrites = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement =
            throw AssertionError("Incomplete delivery state attempted $operationName")
    })

    @Test fun `a missing release edit target retains dates and invalidates only a changed project version`() = runTest {
        for ((currentProject, retainedVersion) in listOf("" to "", "project" to "version")) {
            val model = decodeClientState<WorkOpsReleaseEditModel>("""{"id":"release","canManage":true,"picker":{"programId":"program","pickProjectId":"$currentProject"}}""")
            model.save("Unsaved release", "Draft", "2027-01-01", "project", "version", noWrites)
            val returned = decodeClientState<WorkOpsReleaseEditModel>(encodeState(model))
            assertTrue(returned.feedback.failed)
            assertEquals("Open the release editor first.", returned.feedback.message)
            assertEquals("Unsaved release", returned.nameDraft)
            assertEquals("2027-01-01", returned.dateDraft)
            assertEquals("project", returned.picker.pickProjectId)
            assertEquals(retainedVersion, returned.picker.pickVersionId)
        }
    }

    @Test fun `missing picker project choices retain a new version draft without creating a version`() = runTest {
        val model = decodeClientState<WorkOpsReleasePickerModel>("""{"programId":"program","creatingVersion":true,"pickProjectId":"project","versionDraft":{}}""")
        model.createVersion("Unsaved version", "Draft", "2026-12-01", "2027-01-01", noWrites)
        val returned = decodeClientState<WorkOpsReleasePickerModel>(encodeState(model))
        assertTrue(returned.versionFeedback.failed)
        assertEquals("Choose an available project.", returned.versionFeedback.message)
        assertEquals("Unsaved version", returned.versionDraft.name)
        assertEquals("2026-12-01", returned.versionDraft.startDate)
        assertEquals("2027-01-01", returned.versionDraft.releaseDate)
    }

    @Test fun `notes without an inspected version retain structured variants without saving`() = runTest {
        val model = decodeClientState<WorkOpsReleaseNotesModel>("""{"id":"release","canManage":true}""")
        val variants = """[{"locale":"en","description":"Unsaved notes"}]"""
        model.save(variants, noWrites)
        val returned = decodeClientState<WorkOpsReleaseNotesModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Open the notes editor first.", returned.feedback.message)
        assertEquals(variants, returned.draft)
        assertEquals(1L, returned.draftRevision)
    }

    @Test fun `a CI approval without a selected run retains the note without addressing another job`() = runTest {
        val model = decodeClientState<WorkOpsReleaseCiModel>("""{"id":"release","read":{"id":"release"}}""")
        model.decide("job", "Retained approval note", true, noWrites)
        val returned = decodeClientState<WorkOpsReleaseCiModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Select an available run first.", returned.feedback.message)
        assertEquals("Retained approval note", returned.comments["job"])
        assertTrue(returned.read.runs.isEmpty())
    }

    @Test fun `patch choices without loaded components remain a rejected draft`() = runTest {
        val model = decodeClientState<WorkOpsReleaseCiModel>("""{"id":"release","patching":true,"read":{"id":"release"}}""")
        model.patch("""{"projects":["project"]}""", noWrites)
        val returned = decodeClientState<WorkOpsReleaseCiModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Choose available affected projects.", returned.feedback.message)
        assertEquals(JsonPrimitive("project"), returned.patchValues.getValue("projects").jsonArray.single())
        assertTrue(returned.patching)
        assertFalse(returned.read.componentsLoaded)
    }

    @Test fun `unconfirmed platform approval retains rejection identity and note without a decision request`() = runTest {
        val model = decodeClientState<WorkOpsApprovalModel>("""{"id":"run","editing":true,"draft":{"runId":"run","nodeId":"node","approved":false}}""")
        model.decide("Reason retained", false, noWrites)
        val returned = decodeClientState<WorkOpsApprovalModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Confirm this decision.", returned.feedback.message)
        assertEquals("run", returned.draft.runId)
        assertEquals("node", returned.draft.nodeId)
        assertFalse(returned.draft.approved)
        assertEquals("Reason retained", returned.draft.note)
        assertEquals("", returned.acknowledgement)
    }

    @Test fun `an incomplete artifact read cannot claim the release has no artifacts`() {
        for ((components, declarations) in listOf(false to false, true to false, false to true, true to true)) {
            val model = decodeClientState<WorkOpsReleaseArtifactsModel>("""{"id":"release","componentsLoaded":$components,"declarationsLoaded":$declarations}""")
            assertEquals(components && declarations, model.verifiedEmpty)
        }
        val failed = decodeClientState<WorkOpsReleaseArtifactsModel>("""{"id":"release","componentsLoaded":true,"declarationsLoaded":true,"publicationErrors":{"run":"Unavailable"}}""")
        assertFalse(failed.verifiedEmpty)
    }

    @Test fun `an unavailable environment family cannot be selected and unknown labels retain readable fallbacks`() {
        val model = decodeClientState<WorkOpsReleaseEnvironmentsModel>("""{"id":"release","programId":"program"}""")
        assertFailsWith<IllegalArgumentException> { model.select("family", "environment") }
        assertTrue(model.selected.isEmpty())
        assertEquals("Project unavailable", model.projectName("project"))
        assertEquals("", model.projectHref("project"))
        assertEquals("Version unavailable", model.versionName(null))
        assertEquals("Version unavailable", model.versionName("version"))
    }
}
