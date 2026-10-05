package bosca.languages.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.languages.model.Language
import bosca.languages.model.LanguageResolutionContext
import bosca.languages.model.LanguageResolutionContextInput
import bosca.languages.model.LanguageTagMapping
import bosca.languages.model.LanguageTagMappingInput
import bosca.languages.service.LanguagesService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Validates that [LanguagePackageInstaller] correctly creates [Language] entries
 * from IETF BCP 47 language tags and delegates persistence to [LanguagesService].
 *
 * The installer is expected to produce 13 languages with non-empty display names
 * and to skip languages that already exist in the service (idempotency).
 */
class LanguagePackageInstallerTest {

    private val installation = PackageInstallation(
        key = "languages",
        name = "Languages",
        versions = listOf(PackageInstallationVersion("1.0.0", listOf("languages")))
    )
    private val version = installation.versions.first()

    private val expectedTags = listOf("en", "es", "es-419", "pt-BR", "fr", "hi", "pl", "tl", "ru", "nl", "ko", "te", "sw")
    private val bibleContextId = UUID.random()

    private fun createService(): LanguagesService {
        val service = mockk<LanguagesService>(relaxed = true)
        coEvery { service.getAll() } returns expectedTags.map { Language(it, it, it) }
        coEvery { service.getResolutionContext("bibles") } returns LanguageResolutionContext(
            id = bibleContextId,
            key = "bibles",
            name = "Bibles",
            fallbackLanguageTag = "eng",
            isProtected = true,
        )
        coEvery { service.getLanguageTagMappings(bibleContextId) } returns emptyList()
        return service
    }

    /**
     * When no languages exist yet, the installer should call [LanguagesService.add]
     * once for each of the 13 supported language tags.
     */
    @Test
    fun `install adds all 13 languages when none exist`() = runTest {
        val service = createService()
        coEvery { service.get(any()) } returns null
        coEvery { service.add(any()) } returns Unit

        val installer = LanguagePackageInstaller(service)
        installer.install(installation, version)

        coVerify(exactly = 13) { service.add(any()) }
    }

    /**
     * Every language produced by the installer must have a non-empty [Language.name]
     * (the canonical English display name) and a non-empty [Language.localName]
     * (the name of the language in its own locale).
     */
    @Test
    fun `all installed languages have non-empty name and localName`() = runTest {
        val service = createService()
        coEvery { service.get(any()) } returns null

        val captured = mutableListOf<Language>()
        coEvery { service.add(capture(captured)) } returns Unit

        val installer = LanguagePackageInstaller(service)
        installer.install(installation, version)

        for (language in captured) {
            assertTrue(language.name.isNotEmpty(), "name should not be empty for tag '${language.tag}'")
            assertTrue(language.localName.isNotEmpty(), "localName should not be empty for tag '${language.tag}'")
        }
    }

    /**
     * The installer should produce exactly the 13 expected IETF language tags,
     * preserving the canonical casing of each tag.
     */
    @Test
    fun `installed languages contain all expected tags`() = runTest {
        val service = createService()
        coEvery { service.get(any()) } returns null

        val captured = mutableListOf<Language>()
        coEvery { service.add(capture(captured)) } returns Unit

        val installer = LanguagePackageInstaller(service)
        installer.install(installation, version)

        val tags = captured.map { it.tag }
        for (tag in expectedTags) {
            assertTrue(tag in tags, "expected tag '$tag' to be installed")
        }
    }

    /**
     * When a language already exists in the service, the installer must not
     * attempt to add it again. This verifies idempotent behaviour: re-running
     * the installer after all languages are present should result in zero adds.
     */
    @Test
    fun `install skips languages that already exist`() = runTest {
        val service = createService()
        coEvery { service.get(any()) } answers {
            val tag = firstArg<String>()
            Language(tag, "existing", "existing")
        }

        val installer = LanguagePackageInstaller(service)
        installer.install(installation, version)

        coVerify(exactly = 0) { service.add(any()) }
    }

    /**
     * When only some languages exist, the installer should add only the missing
     * ones. Here "en" and "fr" are pre-existing, so 11 languages should be added.
     */
    @Test
    fun `install only adds missing languages when some already exist`() = runTest {
        val service = createService()
        val existing = setOf("en", "fr")
        coEvery { service.get(any()) } answers {
            val tag = firstArg<String>()
            if (tag in existing) Language(tag, "existing", "existing") else null
        }
        coEvery { service.add(any()) } returns Unit

        val installer = LanguagePackageInstaller(service)
        installer.install(installation, version)

        coVerify(exactly = 11) { service.add(any()) }
    }

    @Test
    fun `install maps Bosca locales to ISO 639-3 Bible language tags`() = runTest {
        val service = createService()
        coEvery { service.get(any()) } answers {
            val tag = firstArg<String>()
            Language(tag, tag, tag)
        }

        LanguagePackageInstaller(service).install(installation, version)

        coVerify {
            service.setLanguageTagMapping(
                bibleContextId,
                LanguageTagMappingInput(sourceLanguageTag = "en", resolvedLanguageTag = "eng"),
            )
        }
        coVerify {
            service.setLanguageTagMapping(
                bibleContextId,
                LanguageTagMappingInput(sourceLanguageTag = "es", resolvedLanguageTag = "spa"),
            )
        }
        coVerify {
            service.setLanguageTagMapping(
                bibleContextId,
                LanguageTagMappingInput(sourceLanguageTag = "pt-BR", resolvedLanguageTag = "por"),
            )
        }
    }

    @Test
    fun `install seeds the common en-US Bible alias even when it is not a registered locale`() = runTest {
        val service = createService()
        coEvery { service.get(any()) } answers {
            val tag = firstArg<String>()
            Language(tag, tag, tag)
        }

        LanguagePackageInstaller(service).install(installation, version)

        coVerify {
            service.setLanguageTagMapping(
                bibleContextId,
                LanguageTagMappingInput(sourceLanguageTag = "en-US", resolvedLanguageTag = "eng"),
            )
        }
    }

    @Test
    fun `install creates a protected Bible resolution context when missing`() = runTest {
        val service = createService()
        val contextInput = slot<LanguageResolutionContextInput>()
        val created = LanguageResolutionContext(
            id = bibleContextId,
            key = "bibles",
            name = "Bibles",
            fallbackLanguageTag = "eng",
            isProtected = true,
        )
        coEvery { service.get(any()) } answers {
            val tag = firstArg<String>()
            Language(tag, tag, tag)
        }
        coEvery { service.getResolutionContext("bibles") } returns null
        coEvery { service.addResolutionContext(capture(contextInput), isProtected = true) } returns created

        LanguagePackageInstaller(service).install(installation, version)

        assertEquals("bibles", contextInput.captured.key)
        assertEquals("eng", contextInput.captured.fallbackLanguageTag)
        coVerify(exactly = 1) { service.addResolutionContext(any(), isProtected = true) }
    }

    @Test
    fun `install protects an existing context and preserves configured mappings`() = runTest {
        val service = createService()
        val existing = LanguageResolutionContext(
            id = bibleContextId,
            key = "bibles",
            name = "Bibles",
            fallbackLanguageTag = "eng",
            isProtected = false,
        )
        coEvery { service.get(any()) } answers {
            val tag = firstArg<String>()
            Language(tag, tag, tag)
        }
        coEvery { service.getResolutionContext("bibles") } returns existing
        coEvery { service.protectResolutionContext(bibleContextId) } returns existing.copy(isProtected = true)
        coEvery { service.getLanguageTagMappings(bibleContextId) } returns listOf(
            LanguageTagMapping(
                contextId = bibleContextId,
                sourceLanguageTag = "en",
                resolvedLanguageTag = "deu",
            ),
        )

        LanguagePackageInstaller(service).install(installation, version)

        coVerify(exactly = 1) { service.protectResolutionContext(bibleContextId) }
        coVerify(exactly = 0) {
            service.setLanguageTagMapping(
                bibleContextId,
                match { it.sourceLanguageTag == "en" },
            )
        }
    }

    @Test
    fun `install migrates legacy inverse Bible mappings and fallback`() = runTest {
        val service = createService()
        val legacyContext = LanguageResolutionContext(
            id = bibleContextId,
            key = "bibles",
            name = "Bibles",
            description = "Legacy",
            fallbackLanguageTag = "en",
            isProtected = true,
        )
        coEvery { service.get(any()) } answers {
            val tag = firstArg<String>()
            Language(tag, tag, tag)
        }
        coEvery { service.getResolutionContext("bibles") } returns legacyContext
        coEvery { service.getLanguageTagMappings(bibleContextId) } returns listOf(
            LanguageTagMapping(bibleContextId, sourceLanguageTag = "en", resolvedLanguageTag = "en"),
            LanguageTagMapping(bibleContextId, sourceLanguageTag = "eng", resolvedLanguageTag = "en"),
        )

        LanguagePackageInstaller(service).install(installation, version)

        coVerify {
            service.editResolutionContext(
                bibleContextId,
                LanguageResolutionContextInput(
                    key = "bibles",
                    name = "Bibles",
                    description = "Legacy",
                    fallbackLanguageTag = "eng",
                ),
            )
        }
        coVerify { service.deleteLanguageTagMapping(bibleContextId, "eng") }
        coVerify {
            service.setLanguageTagMapping(
                bibleContextId,
                LanguageTagMappingInput(sourceLanguageTag = "en", resolvedLanguageTag = "eng"),
            )
        }
    }
}
