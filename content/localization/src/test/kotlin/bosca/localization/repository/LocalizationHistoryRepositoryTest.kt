@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.localization.LocalizationTestFixture
import bosca.localization.model.TranslationHistoryTables
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.serialization.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Integration tests for `localization.translation_history`. The history table is
 * append-only, so the tests confirm inserts land with every field intact, ordering
 * follows `created` ascending, and the `fromState` null path (initial entry) works.
 */
class LocalizationHistoryRepositoryTest {

    private lateinit var history: LocalizationHistoryRepository

    @BeforeTest
    fun setup() {
        LocalizationTestFixture.ensureSchema()
        LocalizationTestFixture.reset()
        history = LocalizationHistoryRepositoryImpl()
    }

    @AfterTest
    fun tearDown() {
        LocalizationTestFixture.reset()
    }

    @Test
    fun `insert persists a full state transition record`() = LocalizationTestFixture.withDb {
        val translationId = UUID.random()
        val actor = UUID.random()
        val inserted = history.insert(
            translationId = translationId,
            tableName = TranslationHistoryTables.TRANSLATIONS,
            fromState = TranslationState.IN_REVIEW,
            toState = TranslationState.APPROVED,
            changedBy = actor,
            origin = TranslationOrigin.HUMAN,
            originDetail = "reviewer-ui",
            previousText = "hola",
            newText = "hola!"
        )
        assertNotNull(inserted.created)
        assertEquals(translationId, inserted.translationId)
        assertEquals(TranslationHistoryTables.TRANSLATIONS, inserted.tableName)
        assertEquals(TranslationState.IN_REVIEW, inserted.fromState)
        assertEquals(TranslationState.APPROVED, inserted.toState)
        assertEquals(actor, inserted.changedBy)
        assertEquals(TranslationOrigin.HUMAN, inserted.origin)
        assertEquals("reviewer-ui", inserted.originDetail)
        assertEquals("hola", inserted.previousText)
        assertEquals("hola!", inserted.newText)
    }

    @Test
    fun `insert allows a null fromState for the initial entry`() = LocalizationTestFixture.withDb {
        val translationId = UUID.random()
        val inserted = history.insert(
            translationId = translationId,
            tableName = TranslationHistoryTables.TRANSLATIONS,
            fromState = null,
            toState = TranslationState.DRAFT,
            changedBy = null,
            origin = TranslationOrigin.AI,
            originDetail = null,
            previousText = null,
            newText = "initial text"
        )
        assertNull(inserted.fromState)
        assertNull(inserted.previousText)
        assertNull(inserted.changedBy, "system/AI-driven transitions legitimately have a null actor")
    }

    @Test
    fun `getByTranslationId returns rows in insertion order`() = LocalizationTestFixture.withDb {
        val translationId = UUID.random()
        history.insert(translationId, TranslationHistoryTables.TRANSLATIONS, null, TranslationState.DRAFT, null, TranslationOrigin.HUMAN, null, null, "first")
        Thread.sleep(5)
        history.insert(translationId, TranslationHistoryTables.TRANSLATIONS, TranslationState.DRAFT, TranslationState.IN_REVIEW, null, TranslationOrigin.HUMAN, null, "first", "first.1")
        Thread.sleep(5)
        history.insert(translationId, TranslationHistoryTables.TRANSLATIONS, TranslationState.IN_REVIEW, TranslationState.APPROVED, null, TranslationOrigin.HUMAN, null, "first.1", "first.1")

        val log = history.getByTranslationId(translationId)
        assertEquals(3, log.size)
        assertEquals(TranslationState.DRAFT, log[0].toState)
        assertEquals(TranslationState.IN_REVIEW, log[1].toState)
        assertEquals(TranslationState.APPROVED, log[2].toState)
    }

    @Test
    fun `getByTranslationId isolates per translation`() = LocalizationTestFixture.withDb {
        val translationA = UUID.random()
        val translationB = UUID.random()
        history.insert(translationA, TranslationHistoryTables.TRANSLATIONS, null, TranslationState.DRAFT, null, TranslationOrigin.HUMAN, null, null, "a")
        history.insert(translationB, TranslationHistoryTables.TRANSLATIONS, null, TranslationState.DRAFT, null, TranslationOrigin.HUMAN, null, null, "b")

        assertEquals(1, history.getByTranslationId(translationA).size)
        assertEquals(1, history.getByTranslationId(translationB).size)
    }

    @Test
    fun `history rows support all three tableName values`() = LocalizationTestFixture.withDb {
        val translationId = UUID.random()
        listOf(
            TranslationHistoryTables.TRANSLATIONS,
            TranslationHistoryTables.PLURAL_TRANSLATIONS,
            TranslationHistoryTables.DOCUMENT_TRANSLATIONS
        ).forEach { table ->
            history.insert(translationId, table, null, TranslationState.DRAFT, null, TranslationOrigin.HUMAN, null, null, "t")
        }
        val log = history.getByTranslationId(translationId)
        assertEquals(3, log.size)
        assertTrue(log.all { it.tableName in setOf(TranslationHistoryTables.TRANSLATIONS, TranslationHistoryTables.PLURAL_TRANSLATIONS, TranslationHistoryTables.DOCUMENT_TRANSLATIONS) })
    }

    @Test
    fun `getByTranslationId returns empty list for unknown id`() = LocalizationTestFixture.withDb {
        assertTrue(history.getByTranslationId(UUID.random()).isEmpty())
    }
}
