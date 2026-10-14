package bosca.content.metadata.graphql

import bosca.bible.BibleAttribution
import bosca.bible.BibleAudio
import bosca.bible.BibleContentUse
import bosca.bible.providers.model.AddProviderAudioInput
import bosca.bible.providers.model.AddProviderBibleInput
import bosca.bible.providers.model.BibleProviderConfiguration
import bosca.bible.providers.model.BibleProviderConfigurationInput
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.service.BibleProviderService
import bosca.content.metadata.model.Bible
import bosca.content.metadata.service.BibleService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BibleProvidersControllerTest {
    private val providers = mockk<BibleProviderService>()
    private val bibles = mockk<BibleService>()
    private val groups = mockk<GroupEvaluator>()
    private val authentication = mockk<AuthenticationContext>()
    private val queries = BibleProvidersController(providers, groups)
    private val mutations = BibleProvidersMutationController(providers, bibles, groups)
    private val id = UUID.random()
    private val configuration = BibleProviderConfiguration(id, "Provider", "API_BIBLE", true, true)
    private val configurationInput = BibleProviderConfigurationInput("Provider", "API_BIBLE", true, id)
    private val bibleInput = AddProviderBibleInput(id, "edition", "study", setOf(BibleContentUse.DISPLAY))
    private val audioInput = AddProviderAudioInput(id, "recording", setOf(BibleContentUse.PLAYBACK))
    private val bible = Bible(id, 3, "publication", "study", false, false, "Bible", "Local Bible", "Description", "B", "LB", JsonNull)

    @Test
    fun `every administration operation rejects non-administrators before calling services`() = runTest {
        every { groups.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")
        val operations: List<suspend () -> Any?> = listOf(
            { queries.providers(authentication) },
            { queries.configurations(authentication) },
            { queries.catalog(authentication, id, BibleSourceKind.TEXT, null, 0, 20) },
            { queries.sources(authentication, id) },
            { queries.audioTargets(authentication, id, "recording") },
            { mutations.saveConfiguration(authentication, configurationInput) },
            { mutations.test(authentication, id) },
            { mutations.addBible(authentication, bibleInput) },
            { mutations.addAudio(authentication, id, 3, "study", audioInput) },
            { mutations.refresh(authentication, id) },
            { mutations.removeSource(authentication, id, 7) },
            { mutations.removeConfiguration(authentication, id, false) },
        )
        for (operation in operations) assertFailsWith<SecurityException> { operation() }
        coVerify { providers wasNot Called; bibles wasNot Called }
        verify(exactly = operations.size) { groups.verifyHasAdminGroup(authentication) }
    }

    @Test
    fun `administrator queries preserve catalog pagination and exact configuration selections`() = runTest {
        every { groups.verifyHasAdminGroup(authentication) } just Runs
        every { providers.getProviders() } returns listOf("API_BIBLE")
        coEvery { providers.getConfigurations() } returns listOf(configuration)
        coEvery { providers.getCatalog(id, BibleSourceKind.AUDIO, "English", 20, 10) } returns emptyList()
        coEvery { providers.getSources(id) } returns emptyList()
        coEvery { providers.getAudioTargets(id, "recording") } returns listOf(bible)
        assertEquals(listOf("API_BIBLE"), queries.providers(authentication))
        assertEquals(listOf(configuration), queries.configurations(authentication))
        assertEquals(emptyList(), queries.catalog(authentication, id, BibleSourceKind.AUDIO, "English", 20, 10))
        assertEquals(emptyList(), queries.sources(authentication, id))
        assertEquals(listOf(bible), queries.audioTargets(authentication, id, "recording"))
        verify(exactly = 5) { groups.verifyHasAdminGroup(authentication) }
    }

    @Test
    fun `administrator mutations preserve input keys reviewed uses and optimistic versions`() = runTest {
        every { groups.verifyHasAdminGroup(authentication) } just Runs
        coEvery { providers.saveConfiguration(configurationInput) } returns configuration
        coEvery { providers.test(id) } just Runs
        coEvery { providers.addBible(bibleInput) } returns bible
        coEvery { providers.refresh(id) } just Runs
        coEvery { providers.removeSource(id, 7) } just Runs
        coEvery { providers.removeConfiguration(id, false) } just Runs
        assertSame(configuration, mutations.saveConfiguration(authentication, configurationInput))
        assertTrue(mutations.test(authentication, id))
        assertSame(bible, mutations.addBible(authentication, bibleInput))
        assertTrue(mutations.refresh(authentication, id))
        assertTrue(mutations.removeSource(authentication, id, 7))
        assertTrue(mutations.removeConfiguration(authentication, id, false))
        verify(exactly = 6) { groups.verifyHasAdminGroup(authentication) }
    }

    @Test
    fun `recording registration uses the exact variant including disabled variants`() = runTest {
        every { groups.verifyHasAdminGroup(authentication) } just Runs
        val audio = BibleAudio(UUID.random(), "Recording", "Recording", "R", BibleAttribution("Copyright"))
        coEvery { bibles.getVariants(id, 3) } returns listOf(bible.copy(variant = "reader"), bible)
        coEvery { providers.addAudio(bible, audioInput) } returns audio
        assertSame(audio, mutations.addAudio(authentication, id, 3, "study", audioInput))
        coVerify(exactly = 1) { providers.addAudio(bible, audioInput) }
    }

    @Test
    fun `missing recording target fails without selecting another variant`() = runTest {
        every { groups.verifyHasAdminGroup(authentication) } just Runs
        coEvery { bibles.getVariants(id, 3) } returns listOf(bible.copy(variant = "reader"))
        assertFailsWith<IllegalStateException> { mutations.addAudio(authentication, id, 3, "study", audioInput) }
        coVerify { providers wasNot Called }
    }

    @Test
    fun `provider failures and cancellation propagate from administrator operations`() = runTest {
        every { groups.verifyHasAdminGroup(authentication) } just Runs
        coEvery { providers.test(id) } throws IOException("Provider unavailable")
        assertFailsWith<IOException> { mutations.test(authentication, id) }
        coEvery { providers.refresh(id) } throws CancellationException("Cancelled")
        assertFailsWith<CancellationException> { mutations.refresh(authentication, id) }
    }
}
