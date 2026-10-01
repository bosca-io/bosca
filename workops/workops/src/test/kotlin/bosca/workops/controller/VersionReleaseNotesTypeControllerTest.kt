package bosca.workops.controller

import bosca.serialization.UUID
import bosca.workops.model.release.VersionReleaseNotes
import bosca.workops.model.version.Version
import bosca.workops.service.VersionService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VersionReleaseNotesTypeControllerTest {

    private val versionService = mockk<VersionService>()
    private val controller = VersionReleaseNotesTypeController(versionService)

    @Test
    fun `fields expose the localization-backed view and resolve its WorkOps version`() = runTest {
        val version = Version(
            id = UUID.random(), projectId = UUID.random(), name = "1.4.0", sequenceNumber = 4,
        )
        val notes = VersionReleaseNotes(
            id = UUID.random(),
            versionId = version.id,
            sourceLocale = "en-US",
            manuallyEdited = true,
            variants = Json.parseToJsonElement("[]"),
        )
        coEvery { versionService.getById(version.id) } returns version

        assertEquals(notes.id, controller.id(notes))
        assertEquals(notes.versionId, controller.versionId(notes))
        assertEquals("en-US", controller.sourceLocale(notes))
        assertEquals(notes.generatedAt, controller.generatedAt(notes))
        assertTrue(controller.manuallyEdited(notes))
        assertEquals(notes.variants, controller.variants(notes))
        assertEquals(version, controller.workOpsVersion(notes))
    }

    @Test
    fun `version resolver fails loudly when the owning version disappeared`() = runTest {
        val notes = VersionReleaseNotes(
            versionId = UUID.random(), sourceLocale = "en-US", variants = Json.parseToJsonElement("[]"),
        )
        coEvery { versionService.getById(notes.versionId) } returns null

        val error = assertFailsWith<IllegalStateException> { controller.workOpsVersion(notes) }
        assertTrue(notes.versionId.toString() in error.message.orEmpty())
    }
}
