package bosca.messages.pages.localization

import bosca.localization.model.LocalizationProject
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationTranslation
import bosca.localization.service.LocalizationService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessageLocalizationsTest {

    @Test
    fun `fallbackChain expands a region tag to base and source`() {
        assertEquals(listOf("es-419", "es", "en"), MessageLocalizations.fallbackChain("es-419"))
    }

    @Test
    fun `fallbackChain dedupes when the tag is the source language`() {
        assertEquals(listOf("en"), MessageLocalizations.fallbackChain("en"))
    }

    @Test
    fun `fallbackChain appends the source language for a base tag`() {
        assertEquals(listOf("fr", "en"), MessageLocalizations.fallbackChain("fr"))
    }

    @Test
    fun `load resolves each key through the fallback chain`() = runTest {
        val project = LocalizationProject(id = UUID.random(), name = "Bosca")
        val brand = LocalizationString(id = UUID.random(), projectId = project.id, key = "site.name")
        val subject = LocalizationString(id = UUID.random(), projectId = project.id, key = "email.verification.subject")

        val service = mockk<LocalizationService>(relaxed = true)
        coEvery { service.getProjects() } returns listOf(project)
        coEvery { service.getStrings(project.id, any(), any()) } returns listOf(brand, subject)
        // brand only exists in the source language: es-419 -> es -> en wins on en
        coEvery { service.getTranslations(brand.id) } returns listOf(
            LocalizationTranslation(stringId = brand.id, languageTag = "en", text = "Bosca")
        )
        // subject has es (but not es-419): es-419 -> es wins on es
        coEvery { service.getTranslations(subject.id) } returns listOf(
            LocalizationTranslation(stringId = subject.id, languageTag = "en", text = "Please verify your email address"),
            LocalizationTranslation(stringId = subject.id, languageTag = "es", text = "Verifica tu correo")
        )

        val localization = MessageLocalizations.load(service, "Bosca", "es-419")

        assertEquals("Bosca", localization.lookup("site.name"))
        assertEquals("Verifica tu correo", localization.lookup("email.verification.subject"))
    }

    @Test
    fun `load fails when the messages project is missing`() = runTest {
        val service = mockk<LocalizationService>(relaxed = true)
        coEvery { service.getProjects() } returns emptyList()
        assertFailsWith<IllegalStateException> {
            MessageLocalizations.load(service, "Bosca", "en")
        }
    }
}
