package bosca.ai.memory

import bosca.ai.configuration.ProviderConfiguration
import bosca.ai.installer.MemoryInstaller
import bosca.content.metadata.model.MemoryAttributes
import bosca.trait.model.Trait
import bosca.trait.model.TraitInput
import bosca.trait.service.TraitService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryInstallerTest {
    @Test fun `memory package and installer are versioned and rerunning preserves the existing trait`() = runTest {
        val traits = mockk<TraitService>()
        var saved: Trait? = null
        coEvery { traits.get(MemoryAttributes.TRAIT) } answers { saved }
        coEvery { traits.add(any()) } answers {
            val input = firstArg<TraitInput>()
            assertEquals(listOf(MemoryAttributes.CONTENT_TYPE), input.contentTypes)
            Trait(input.id, input.name, input.description, input.deleteWorkflowId).also { saved = it }
        }
        val installation = ProviderConfiguration().aiPackage()
        val version = installation.versions.last()
        assertEquals("1.1.0", version.version)
        assertEquals(listOf("memories-installer"), version.installerNames)
        val installer = MemoryInstaller(traits)
        repeat(2) { installer.install(installation, version) }
        coVerify(exactly = 1) { traits.add(any()) }
    }
}
