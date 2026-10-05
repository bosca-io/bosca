@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.localization.LocalizationTestFixture
import bosca.localization.model.LocalizationProject
import bosca.localization.model.LocalizationString
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
 * Integration tests for `localization.translations`. Locks in enum casting, upsert
 * semantics, the join-based `getByProjectAnd*` queries, and the bulkTransition SQL.
 */
class LocalizationTranslationRepositoryTest {

    private lateinit var projects: LocalizationProjectRepository
    private lateinit var strings: LocalizationStringRepository
    private lateinit var translations: LocalizationTranslationRepository
    private lateinit var projectId: UUID
    private lateinit var stringA: UUID
    private lateinit var stringB: UUID

    @BeforeTest
    fun setup() {
        LocalizationTestFixture.ensureSchema()
        LocalizationTestFixture.reset()
        LocalizationTestFixture.seedLanguage("en")
        LocalizationTestFixture.seedLanguage("es")
        LocalizationTestFixture.seedLanguage("fr")
        projects = LocalizationProjectRepositoryImpl()
        strings = LocalizationStringRepositoryImpl()
        translations = LocalizationTranslationRepositoryImpl()
        LocalizationTestFixture.withDb {
            projectId = projects.add(LocalizationProject(name = "P", sourceLanguage = "en")).id
            stringA = strings.add(LocalizationString(projectId = projectId, key = "a")).id
            stringB = strings.add(LocalizationString(projectId = projectId, key = "b")).id
        }
    }

    @AfterTest
    fun tearDown() {
        LocalizationTestFixture.reset()
    }

    @Test
    fun `upsert inserts a new row with the provided state and origin`() = LocalizationTestFixture.withDb {
        val t = translations.upsert(
            stringId = stringA,
            languageTag = "es",
            text = "hola",
            state = TranslationState.AI_GENERATED,
            origin = TranslationOrigin.AI,
            originDetail = "gpt-4",
            createdBy = null
        )
        assertEquals("hola", t.text)
        assertEquals(TranslationState.AI_GENERATED, t.state)
        assertEquals(TranslationOrigin.AI, t.origin)
        assertEquals("gpt-4", t.originDetail)
    }

    @Test
    fun `upsert overwrites text, state, origin on conflict and bumps modified`() = LocalizationTestFixture.withDb {
        val first = translations.upsert(stringA, "es", "hola", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        Thread.sleep(5)
        val second = translations.upsert(stringA, "es", "adios", TranslationState.IN_REVIEW, TranslationOrigin.IMPORT, "file", null)
        assertEquals(first.id, second.id, "upsert should return the same row via the unique (string_id, language_tag) constraint")
        assertEquals("adios", second.text)
        assertEquals(TranslationState.IN_REVIEW, second.state)
        assertEquals(TranslationOrigin.IMPORT, second.origin)
        assertEquals("file", second.originDetail)
        assertTrue(second.modified!! >= first.modified!!)
    }

    @Test
    fun `getByStringIdAndLanguage returns null when absent`() = LocalizationTestFixture.withDb {
        assertNull(translations.getByStringIdAndLanguage(stringA, "es"))
        translations.upsert(stringA, "es", "hola", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        assertNotNull(translations.getByStringIdAndLanguage(stringA, "es"))
    }

    @Test
    fun `getByProjectAndState filters with enum cast`() = LocalizationTestFixture.withDb {
        translations.upsert(stringA, "es", "a1", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        translations.upsert(stringB, "es", "b1", TranslationState.PUBLISHED, TranslationOrigin.HUMAN, null, null)
        translations.upsert(stringB, "fr", "b2", TranslationState.PUBLISHED, TranslationOrigin.HUMAN, null, null)

        val drafts = translations.getByProjectAndState(projectId, TranslationState.DRAFT)
        val published = translations.getByProjectAndState(projectId, TranslationState.PUBLISHED)

        assertEquals(1, drafts.size)
        assertEquals(2, published.size)
    }

    @Test
    fun `getByProjectAndOrigin filters with enum cast`() = LocalizationTestFixture.withDb {
        translations.upsert(stringA, "es", "x", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        translations.upsert(stringB, "es", "y", TranslationState.DRAFT, TranslationOrigin.AI, null, null)

        val byAi = translations.getByProjectAndOrigin(projectId, TranslationOrigin.AI)
        assertEquals(1, byAi.size)
        assertEquals("y", byAi.first().text)
    }

    @Test
    fun `getByProjectAndLanguage returns every row in that language only`() = LocalizationTestFixture.withDb {
        translations.upsert(stringA, "es", "a-es", TranslationState.PUBLISHED, TranslationOrigin.HUMAN, null, null)
        translations.upsert(stringA, "fr", "a-fr", TranslationState.PUBLISHED, TranslationOrigin.HUMAN, null, null)
        translations.upsert(stringB, "es", "b-es", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)

        val es = translations.getByProjectAndLanguage(projectId, "es").map { it.text }.sorted()
        assertEquals(listOf("a-es", "b-es"), es)
    }

    @Test
    fun `transitionStateWithReviewer records the reviewer and bumps reviewed_at`() = LocalizationTestFixture.withDb {
        val t = translations.upsert(stringA, "es", "hola", TranslationState.IN_REVIEW, TranslationOrigin.HUMAN, null, null)
        val reviewerId = UUID.random()

        val approved = translations.transitionStateWithReviewer(t.id, TranslationState.APPROVED, reviewerId)!!
        assertEquals(TranslationState.APPROVED, approved.state)
        assertEquals(reviewerId, approved.reviewedBy)
        assertNotNull(approved.reviewedAt)
    }

    @Test
    fun `transitionStateWithoutReviewer leaves prior reviewer intact`() = LocalizationTestFixture.withDb {
        val t = translations.upsert(stringA, "es", "hola", TranslationState.IN_REVIEW, TranslationOrigin.HUMAN, null, null)
        val reviewerId = UUID.random()
        translations.transitionStateWithReviewer(t.id, TranslationState.APPROVED, reviewerId)

        val nulled = translations.transitionStateWithoutReviewer(t.id, TranslationState.PUBLISHED)!!
        assertEquals(TranslationState.PUBLISHED, nulled.state)
        assertEquals(reviewerId, nulled.reviewedBy, "a reviewer-less transition must not erase the previous reviewer")
    }

    @Test
    fun `transitionStateWithReviewer returns null for a missing id`() = LocalizationTestFixture.withDb {
        assertNull(translations.transitionStateWithReviewer(UUID.random(), TranslationState.PUBLISHED, UUID.random()))
    }

    @Test
    fun `transitionStateWithoutReviewer returns null for a missing id`() = LocalizationTestFixture.withDb {
        assertNull(translations.transitionStateWithoutReviewer(UUID.random(), TranslationState.PUBLISHED))
    }

    @Test
    fun `getByProjectLanguageAndState returns only rows matching all three filters`() = LocalizationTestFixture.withDb {
        translations.upsert(stringA, "es", "a", TranslationState.APPROVED, TranslationOrigin.HUMAN, null, null)
        translations.upsert(stringB, "es", "b", TranslationState.APPROVED, TranslationOrigin.HUMAN, null, null)
        translations.upsert(stringB, "fr", "c", TranslationState.APPROVED, TranslationOrigin.HUMAN, null, null)
        translations.upsert(stringA, "fr", "d", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)

        val approvedSpanish = translations.getByProjectLanguageAndState(projectId, "es", TranslationState.APPROVED)
        assertEquals(setOf("a", "b"), approvedSpanish.map { it.text }.toSet(), "exactly the two Spanish APPROVED rows must match")

        val approvedFrench = translations.getByProjectLanguageAndState(projectId, "fr", TranslationState.APPROVED)
        assertEquals(setOf("c"), approvedFrench.map { it.text }.toSet(), "French filter isolates from Spanish")

        val draftsInSpanish = translations.getByProjectLanguageAndState(projectId, "es", TranslationState.DRAFT)
        assertTrue(draftsInSpanish.isEmpty(), "no Spanish rows are in DRAFT state")
    }

    @Test
    fun `deleteByStringAndLanguage clears the targeted row only`() = LocalizationTestFixture.withDb {
        translations.upsert(stringA, "es", "a", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        translations.upsert(stringA, "fr", "b", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        translations.deleteByStringAndLanguage(stringA, "es")
        assertNull(translations.getByStringIdAndLanguage(stringA, "es"))
        assertNotNull(translations.getByStringIdAndLanguage(stringA, "fr"))
    }

    @Test
    fun `deleting the string cascades to its translations`() = LocalizationTestFixture.withDb {
        translations.upsert(stringA, "es", "hola", TranslationState.PUBLISHED, TranslationOrigin.HUMAN, null, null)
        strings.deleteById(stringA)
        assertNull(translations.getByStringIdAndLanguage(stringA, "es"))
    }

    @Test
    fun `getProgressCounts aggregates state and origin filters`() = LocalizationTestFixture.withDb {
        translations.upsert(stringA, "es", "a", TranslationState.PUBLISHED, TranslationOrigin.HUMAN, null, null)
        translations.upsert(stringB, "es", "b", TranslationState.AI_GENERATED, TranslationOrigin.AI, null, null)

        val counts = translations.getProgressCounts(projectId, "es")
        assertNotNull(counts)
        assertEquals(2, counts.translatedStrings)
        assertEquals(1, counts.approvedStrings, "APPROVED counter should include APPROVED + PUBLISHED")
        assertEquals(1, counts.publishedStrings)
        assertEquals(1, counts.aiGeneratedStrings)
        assertEquals(1, counts.humanTranslatedStrings)
    }
}
