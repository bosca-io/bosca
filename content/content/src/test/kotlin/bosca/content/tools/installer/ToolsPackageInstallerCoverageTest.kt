package bosca.content.tools.installer

import bosca.content.tools.model.TemplateAttributeTool
import bosca.content.tools.model.TemplateAttributeToolInput
import bosca.content.tools.service.TemplateAttributeToolService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ToolsPackageInstallerCoverageTest {

    private val service = mockk<TemplateAttributeToolService>(relaxed = true)
    private val installer = ToolsPackageInstaller(service)

    private val installation = PackageInstallation(
        key = "tools",
        name = "Tools",
        versions = listOf(
            PackageInstallationVersion(version = "1.0.0", installerNames = listOf("tools-installer"))
        )
    )
    private val installationVersion = installation.versions[0]

    private val expectedKeys = listOf(
        "generate-description",
        "generate-discussion-questions",
        "generate-topics",
        "reading-time",
        "generate-mp3-male",
        "generate-mp3-female",
        "generate-image",
    )

    private fun existingTool(key: String) = TemplateAttributeTool(
        id = Uuid.random(),
        key = key,
        name = key,
        query = "q",
    )

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `version is 1_0_0`() {
        assertEquals("1.0.0", installer.version)
    }

    @Test
    fun `install adds every tool when none exist`() = runTest {
        coEvery { service.getAll() } returns emptyList()

        installer.install(installation, installationVersion)

        val added = mutableListOf<TemplateAttributeToolInput>()
        coVerify(exactly = 1) { service.getAll() }
        coVerify(exactly = expectedKeys.size) { service.add(capture(added)) }
        coVerify(exactly = 0) { service.edit(any(), any()) }

        assertEquals(expectedKeys.toSet(), added.map { it.key }.toSet())
    }

    @Test
    fun `install edits every tool when all already exist`() = runTest {
        val existing = expectedKeys.map { existingTool(it) }
        coEvery { service.getAll() } returns existing

        val ids = mutableListOf<Uuid>()
        val inputs = mutableListOf<TemplateAttributeToolInput>()

        installer.install(installation, installationVersion)

        coVerify(exactly = 1) { service.getAll() }
        coVerify(exactly = 0) { service.add(any()) }
        coVerify(exactly = expectedKeys.size) { service.edit(capture(ids), capture(inputs)) }

        // Each edit must target the id of the matching existing tool.
        val byKey = existing.associateBy { it.key }
        ids.zip(inputs).forEach { (id, input) ->
            assertEquals(byKey[input.key]?.id, id)
        }
    }

    @Test
    fun `install mixes add and edit based on the version gate`() = runTest {
        // Only the first two keys already exist; the rest are new.
        val existingKeys = expectedKeys.take(2)
        val existing = existingKeys.map { existingTool(it) }
        coEvery { service.getAll() } returns existing

        installer.install(installation, installationVersion)

        coVerify(exactly = 1) { service.getAll() }
        coVerify(exactly = expectedKeys.size - existingKeys.size) { service.add(any()) }
        coVerify(exactly = existingKeys.size) { service.edit(any(), any()) }
    }

    @Test
    fun `install keys existing tools by their key ignoring extra tools`() = runTest {
        // getAll returns a tool whose key is NOT one the installer manages; it must be
        // ignored and every managed tool treated as new (add).
        coEvery { service.getAll() } returns listOf(existingTool("some-unrelated-tool"))

        installer.install(installation, installationVersion)

        coVerify(exactly = expectedKeys.size) { service.add(any()) }
        coVerify(exactly = 0) { service.edit(any(), any()) }
    }
}
