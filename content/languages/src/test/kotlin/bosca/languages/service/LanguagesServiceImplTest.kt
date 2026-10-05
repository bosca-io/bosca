package bosca.languages.service

import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.languages.model.Language
import bosca.languages.model.LanguageResolutionContext
import bosca.languages.model.LanguageResolutionContextInput
import bosca.languages.model.LanguageTagMapping
import bosca.languages.model.LanguageTagMappingInput
import bosca.languages.repository.LanguagesRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguagesServiceImplTest {

    private val repository = mockk<LanguagesRepository>()
    private val service = LanguagesServiceImpl(repository)

    private val english = Language(tag = "en", name = "English", localName = "English")
    private val spanish = Language(tag = "es", name = "Spanish", localName = "Espanol")

    private suspend fun <T> withTransactionContext(block: suspend () -> T): T {
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        return withContext(connectionManager.asCoroutineContext()) { block() }
    }

    @Test
    fun `getAll delegates to repository`() = runTest {
        coEvery { repository.getAll() } returns listOf(english, spanish)
        val result = service.getAll()
        assertEquals(2, result.size)
        assertEquals("en", result[0].tag)
        assertEquals("es", result[1].tag)
    }

    @Test
    fun `getAll returns empty list when no languages exist`() = runTest {
        coEvery { repository.getAll() } returns emptyList()
        val result = service.getAll()
        assertEquals(0, result.size)
    }

    @Test
    fun `get returns language when found`() = runTest {
        coEvery { repository.get("en") } returns english
        val result = service.get("en")
        assertEquals(english, result)
    }

    @Test
    fun `get returns null when not found`() = runTest {
        coEvery { repository.get("fr") } returns null
        val result = service.get("fr")
        assertNull(result)
    }

    @Test
    fun `add delegates to repository`() = runTest {
        coEvery { repository.add(english) } returns english
        service.add(english)
        coVerify(exactly = 1) { repository.add(english) }
    }

    @Test
    fun `edit delegates to repository`() = runTest {
        val updated = english.copy(name = "English (Updated)")
        coEvery { repository.update(updated) } returns updated
        service.edit(updated)
        coVerify(exactly = 1) { repository.update(updated) }
    }

    @Test
    fun `delete delegates to repository`() = runTest {
        coEvery { repository.delete("en") } just Runs
        service.delete("en")
        coVerify(exactly = 1) { repository.delete("en") }
    }

    @Test
    fun `blank language tag resolves to the context fallback`() = runTest {
        val context = LanguageResolutionContext(UUID.random(), "recommendations", "Recommendations", fallbackLanguageTag = "en")
        coEvery { repository.getResolutionContextByKey("recommendations") } returns context

        val result = service.resolveLanguageTag("recommendations", "   ")

        assertEquals("en", result.resolvedLanguageTag)
        assertNull(result.normalizedLanguageTag)
        assertTrue(result.usedFallback)
    }

    @Test
    fun `regional language tag uses the most specific configured parent mapping`() = runTest {
        val context = LanguageResolutionContext(UUID.random(), "bibles", "Bibles", fallbackLanguageTag = "eng")
        coEvery { repository.getResolutionContextByKey("bibles") } returns context
        coEvery { repository.getLanguageTagMapping(context.id, "en-US") } returns null
        coEvery { repository.getLanguageTagMapping(context.id, "en") } returns
            LanguageTagMapping(context.id, "en", "eng")

        val result = service.resolveLanguageTag("bibles", " EN_us ")

        assertEquals("en-US", result.normalizedLanguageTag)
        assertEquals("eng", result.resolvedLanguageTag)
        assertFalse(result.usedFallback)
    }

    @Test
    fun `unmapped language tag resolves to the context fallback`() = runTest {
        val context = LanguageResolutionContext(UUID.random(), "recommendations", "Recommendations", fallbackLanguageTag = "en")
        coEvery { repository.getResolutionContextByKey("recommendations") } returns context
        coEvery { repository.getLanguageTagMapping(context.id, "de-DE") } returns null
        coEvery { repository.getLanguageTagMapping(context.id, "de") } returns null

        val result = service.resolveLanguageTag("recommendations", "de-DE")

        assertEquals("en", result.resolvedLanguageTag)
        assertTrue(result.usedFallback)
    }

    @Test
    fun `protected context key cannot be changed`() = runTest {
        val id = UUID.random()
        val context = LanguageResolutionContext(
            id = id,
            key = "recommendations",
            name = "Recommendations",
            fallbackLanguageTag = "en",
            isProtected = true,
        )
        coEvery { repository.getResolutionContextById(id) } returns context

        val error = assertFailsWith<IllegalStateException> {
            withTransactionContext {
                service.editResolutionContext(
                    id,
                    LanguageResolutionContextInput("renamed", "Recommendations", fallbackLanguageTag = "en"),
                )
            }
        }

        assertEquals("Protected language resolution context keys cannot be changed", error.message)
        coVerify(exactly = 0) { repository.updateResolutionContext(any()) }
    }

    @Test
    fun `protected context fallback and display metadata remain editable`() = runTest {
        val id = UUID.random()
        val context = LanguageResolutionContext(
            id = id,
            key = "recommendations",
            name = "Recommendations",
            fallbackLanguageTag = "en",
            isProtected = true,
        )
        coEvery { repository.getResolutionContextById(id) } returns context
        coEvery { repository.updateResolutionContext(any()) } answers { firstArg() }

        val updated = withTransactionContext {
            service.editResolutionContext(
                id,
                LanguageResolutionContextInput(
                    key = "recommendations",
                    name = "Recommendation models",
                    description = "Updated",
                    fallbackLanguageTag = "es",
                ),
            )
        }

        assertEquals("es", updated.fallbackLanguageTag)
        assertEquals("Recommendation models", updated.name)
        assertTrue(updated.isProtected)
    }

    @Test
    fun `context fallback may use a domain language tag`() = runTest {
        coEvery { repository.addResolutionContext(any()) } answers { firstArg() }

        val context = withTransactionContext {
            service.addResolutionContext(
                LanguageResolutionContextInput(
                    key = "bibles",
                    name = "Bibles",
                    fallbackLanguageTag = "eng",
                ),
            )
        }

        assertEquals("eng", context.fallbackLanguageTag)
        coVerify(exactly = 0) { repository.get("eng") }
    }

    @Test
    fun `mapping resolves a Bosca locale to a domain language tag`() = runTest {
        val context = LanguageResolutionContext(
            id = UUID.random(),
            key = "bibles",
            name = "Bibles",
            fallbackLanguageTag = "eng",
        )
        coEvery { repository.getResolutionContextById(context.id) } returns context
        coEvery { repository.setLanguageTagMapping(any()) } answers { firstArg() }

        val mapping = withTransactionContext {
            service.setLanguageTagMapping(
                context.id,
                LanguageTagMappingInput(sourceLanguageTag = "en", resolvedLanguageTag = "eng"),
            )
        }

        assertEquals("en", mapping.sourceLanguageTag)
        assertEquals("eng", mapping.resolvedLanguageTag)
        coVerify(exactly = 0) { repository.get(any()) }
    }

    @Test
    fun `protected context cannot be deleted`() = runTest {
        val id = UUID.random()
        val context = LanguageResolutionContext(
            id = id,
            key = "recommendations",
            name = "Recommendations",
            fallbackLanguageTag = "en",
            isProtected = true,
        )
        coEvery { repository.getResolutionContextById(id) } returns context

        val error = assertFailsWith<IllegalStateException> {
            withTransactionContext { service.deleteResolutionContext(id) }
        }

        assertEquals("Protected language resolution contexts cannot be deleted", error.message)
        coVerify(exactly = 0) { repository.deleteResolutionContext(id) }
    }

    @Test
    fun `context protection is persisted`() = runTest {
        val context = LanguageResolutionContext(
            id = UUID.random(),
            key = "recommendations",
            name = "Recommendations",
            fallbackLanguageTag = "en",
        )
        val protectedContext = context.copy(isProtected = true)
        coEvery { repository.getResolutionContextById(context.id) } returns context
        coEvery { repository.protectResolutionContext(context.id) } returns protectedContext

        val result = withTransactionContext { service.protectResolutionContext(context.id) }

        assertTrue(result.isProtected)
        coVerify(exactly = 1) { repository.protectResolutionContext(context.id) }
    }
}
