package bosca.messages.pages.localization

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.languages.model.Language
import bosca.languages.service.LanguagesService
import bosca.localization.model.LocalizationProject
import bosca.localization.model.LocalizationProjectInput
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationStringInput
import bosca.localization.model.LocalizationTranslation
import bosca.localization.model.LocalizationTranslationInput
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.localization.service.LocalizationService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MessageLocalizationInstallerTest {

    /** Minimal in-memory state backing the faked [LocalizationService], modelling the bits the installer uses. */
    private class State {
        val projects = mutableListOf<LocalizationProject>()
        val strings = mutableMapOf<Pair<UUID, String>, LocalizationString>()
        val translations = mutableMapOf<Pair<UUID, String>, LocalizationTranslation>()
        val languages = mutableMapOf<String, Language>()
        var setTranslationCalls = 0
        var bulkTransitionCalls = 0
    }

    private fun fakeLanguages(state: State): LanguagesService {
        val service = mockk<LanguagesService>(relaxed = true)
        coEvery { service.get(any()) } answers { state.languages[firstArg<String>()] }
        coEvery { service.add(any()) } answers {
            val language = firstArg<Language>()
            state.languages[language.tag] = language
        }
        return service
    }

    private fun fakeService(state: State): LocalizationService {
        val service = mockk<LocalizationService>(relaxed = true)
        coEvery { service.getProjects() } answers { state.projects.toList() }
        coEvery { service.addProject(any()) } answers {
            val input = firstArg<LocalizationProjectInput>()
            LocalizationProject(id = UUID.random(), name = input.name, sourceLanguage = input.sourceLanguage)
                .also { state.projects.add(it) }
        }
        coEvery { service.getStringByKey(any(), any()) } answers {
            state.strings[firstArg<UUID>() to secondArg<String>()]
        }
        coEvery { service.addString(any()) } answers {
            val input = firstArg<LocalizationStringInput>()
            LocalizationString(id = UUID.random(), projectId = input.projectId, key = input.key)
                .also { state.strings[input.projectId to input.key] = it }
        }
        coEvery { service.getTranslation(any(), any()) } answers {
            state.translations[firstArg<UUID>() to secondArg<String>()]
        }
        coEvery { service.setTranslation(any(), any()) } answers {
            val input = firstArg<LocalizationTranslationInput>()
            state.setTranslationCalls++
            // Imported translations enter in DRAFT, matching TranslationStateMachine.initialState(IMPORT).
            LocalizationTranslation(
                id = UUID.random(),
                stringId = input.stringId,
                languageTag = input.languageTag,
                text = input.text,
                state = TranslationState.DRAFT,
                origin = input.origin,
                originDetail = input.originDetail
            ).also { state.translations[input.stringId to input.languageTag] = it }
        }
        coEvery { service.bulkTransition(any(), any(), any(), any(), any()) } answers {
            state.bulkTransitionCalls++
            val languageTag = secondArg<String>()
            val from = arg<TranslationState>(2)
            val to = arg<TranslationState>(3)
            val matches = state.translations.filter { it.key.second == languageTag && it.value.state == from }
            matches.forEach { (key, translation) -> state.translations[key] = translation.copy(state = to) }
            matches.size
        }
        coEvery { service.transitionTranslation(any(), any(), any()) } answers {
            val id = firstArg<UUID>()
            val targetState = secondArg<TranslationState>()
            val entry = state.translations.entries.single { it.value.id == id }
            entry.value.copy(state = targetState).also { entry.setValue(it) }
        }
        return service
    }

    private val installation = PackageInstallation("messages-localization", "Message Localization", emptyList())
    private val version = PackageInstallationVersion("1.1.0", emptyList())

    @Test
    fun `seeds the Bosca brand defaults`() = runTest {
        val state = State()
        val installer = MessageLocalizationInstaller(fakeLanguages(state), fakeService(state), "Bosca")

        installer.install(installation, version)

        assertEquals(1, state.projects.size)
        val projectId = state.projects.single().id
        assertEquals("Bosca", state.projects.single().name)

        val siteName = assertNotNull(state.strings[projectId to "site.name"])
        assertEquals("Bosca", state.translations[siteName.id to "en"]?.text)
        val welcome = assertNotNull(state.strings[projectId to "email.verification"])
        assertEquals("Welcome to Bosca!", state.translations[welcome.id to "en"]?.text)
        val invitation = assertNotNull(state.strings[projectId to "channel-invitation.push.title"])
        assertEquals("Channel invitation", state.translations[invitation.id to "en"]?.text)
    }

    @Test
    fun `approves page strings and publishes hosted message strings`() = runTest {
        val state = State()
        val installer = MessageLocalizationInstaller(fakeLanguages(state), fakeService(state), "Bosca")

        installer.install(installation, version)

        assertTrue(state.translations.isNotEmpty())
        val projectId = state.projects.single().id
        val pageString = assertNotNull(state.strings[projectId to "site.name"])
        val messageString = assertNotNull(state.strings[projectId to "channel-invitation.push.title"])
        assertEquals(TranslationState.APPROVED, state.translations[pageString.id to "en"]?.state)
        assertEquals(TranslationState.PUBLISHED, state.translations[messageString.id to "en"]?.state)
        assertTrue(state.translations.values.all { it.state in setOf(TranslationState.APPROVED, TranslationState.PUBLISHED) })
    }

    @Test
    fun `re-running the installer writes nothing new`() = runTest {
        val state = State()
        val installer = MessageLocalizationInstaller(fakeLanguages(state), fakeService(state), "Bosca")

        installer.install(installation, version)
        val writesAfterFirstRun = state.setTranslationCalls

        installer.install(installation, version)

        assertEquals(writesAfterFirstRun, state.setTranslationCalls)
        assertEquals(1, state.projects.size)
        assertEquals(0, state.bulkTransitionCalls)
    }

    @Test
    fun `re-running preserves operator text and workflow state`() = runTest {
        val state = State()
        val installer = MessageLocalizationInstaller(fakeLanguages(state), fakeService(state), "Bosca")
        installer.install(installation, version)
        val projectId = state.projects.single().id
        val siteName = assertNotNull(state.strings[projectId to "site.name"])
        val translationKey = siteName.id to "en"
        state.translations[translationKey] = assertNotNull(state.translations[translationKey]).copy(
            text = "Operator brand",
            state = TranslationState.DRAFT,
            origin = TranslationOrigin.HUMAN,
            originDetail = null,
        )
        val writesAfterFirstRun = state.setTranslationCalls

        installer.install(installation, version)

        assertEquals(writesAfterFirstRun, state.setTranslationCalls)
        assertEquals("Operator brand", state.translations[translationKey]?.text)
        assertEquals(TranslationState.DRAFT, state.translations[translationKey]?.state)
        assertEquals(0, state.bulkTransitionCalls)
    }
}
