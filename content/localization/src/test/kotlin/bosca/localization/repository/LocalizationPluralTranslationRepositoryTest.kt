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
 * Integration tests for `localization.plural_translations`. Exercises the three-column
 * unique index `(string_id, language_tag, plural_category)` and confirms both the
 * with-reviewer and without-reviewer transition variants.
 */
class LocalizationPluralTranslationRepositoryTest {

    private lateinit var strings: LocalizationStringRepository
    private lateinit var plurals: LocalizationPluralTranslationRepository
    private lateinit var projectId: UUID
    private lateinit var stringId: UUID

    @BeforeTest
    fun setup() {
        LocalizationTestFixture.ensureSchema()
        LocalizationTestFixture.reset()
        LocalizationTestFixture.seedLanguage("en")
        LocalizationTestFixture.seedLanguage("ar")
        strings = LocalizationStringRepositoryImpl()
        plurals = LocalizationPluralTranslationRepositoryImpl()
        LocalizationTestFixture.withDb {
            projectId = LocalizationProjectRepositoryImpl().add(LocalizationProject(name = "P", sourceLanguage = "en")).id
            stringId = strings.add(LocalizationString(projectId = projectId, key = "items_count", plural = true)).id
        }
    }

    @AfterTest
    fun tearDown() {
        LocalizationTestFixture.reset()
    }

    @Test
    fun `upsert inserts one row per CLDR plural category`() = LocalizationTestFixture.withDb {
        plurals.upsert(stringId, "en", "one", "1 item", TranslationState.PUBLISHED, TranslationOrigin.HUMAN, null, null)
        plurals.upsert(stringId, "en", "other", "{count} items", TranslationState.PUBLISHED, TranslationOrigin.HUMAN, null, null)

        val rows = plurals.getByStringAndLanguage(stringId, "en")
        assertEquals(2, rows.size)
        val byCategory = rows.associateBy { it.pluralCategory }
        assertEquals("1 item", byCategory["one"]!!.text)
        assertEquals("{count} items", byCategory["other"]!!.text)
    }

    @Test
    fun `upsert on same category overwrites text and state`() = LocalizationTestFixture.withDb {
        val first = plurals.upsert(stringId, "en", "one", "1 item", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        val second = plurals.upsert(stringId, "en", "one", "one item", TranslationState.PUBLISHED, TranslationOrigin.IMPORT, "file", null)
        assertEquals(first.id, second.id, "upsert must return the same row via the three-column unique key")
        assertEquals("one item", second.text)
        assertEquals(TranslationState.PUBLISHED, second.state)
        assertEquals(TranslationOrigin.IMPORT, second.origin)
    }

    @Test
    fun `different plural categories in the same language share a string without conflict`() = LocalizationTestFixture.withDb {
        // Arabic has six plural forms; storing all six must not conflict.
        for (cat in listOf("zero", "one", "two", "few", "many", "other")) {
            plurals.upsert(stringId, "ar", cat, cat, TranslationState.PUBLISHED, TranslationOrigin.HUMAN, null, null)
        }
        val rows = plurals.getByStringAndLanguage(stringId, "ar")
        assertEquals(6, rows.size)
    }

    @Test
    fun `transitionStateWithReviewer records reviewer and reviewed_at on a plural row`() = LocalizationTestFixture.withDb {
        val inserted = plurals.upsert(stringId, "en", "one", "1", TranslationState.IN_REVIEW, TranslationOrigin.HUMAN, null, null)
        val reviewer = UUID.random()
        val approved = plurals.transitionStateWithReviewer(inserted.id, TranslationState.APPROVED, reviewer)!!
        assertEquals(TranslationState.APPROVED, approved.state)
        assertEquals(reviewer, approved.reviewedBy)
        assertNotNull(approved.reviewedAt)
    }

    @Test
    fun `transitionStateWithoutReviewer leaves reviewer intact`() = LocalizationTestFixture.withDb {
        val inserted = plurals.upsert(stringId, "en", "one", "1", TranslationState.IN_REVIEW, TranslationOrigin.HUMAN, null, null)
        val reviewer = UUID.random()
        plurals.transitionStateWithReviewer(inserted.id, TranslationState.APPROVED, reviewer)
        val nulled = plurals.transitionStateWithoutReviewer(inserted.id, TranslationState.PUBLISHED)!!
        assertEquals(reviewer, nulled.reviewedBy)
        assertEquals(TranslationState.PUBLISHED, nulled.state)
    }

    @Test
    fun `transition returns null for a missing id`() = LocalizationTestFixture.withDb {
        assertNull(plurals.transitionStateWithReviewer(UUID.random(), TranslationState.PUBLISHED, UUID.random()))
        assertNull(plurals.transitionStateWithoutReviewer(UUID.random(), TranslationState.PUBLISHED))
    }

    @Test
    fun `deleteByStringAndLanguage clears every plural form for that language only`() = LocalizationTestFixture.withDb {
        plurals.upsert(stringId, "en", "one", "1", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        plurals.upsert(stringId, "en", "other", "many", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        plurals.upsert(stringId, "ar", "one", "أ", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)

        plurals.deleteByStringAndLanguage(stringId, "en")

        assertTrue(plurals.getByStringAndLanguage(stringId, "en").isEmpty())
        assertEquals(1, plurals.getByStringAndLanguage(stringId, "ar").size)
    }

    @Test
    fun `getByProjectAndLanguage aggregates across every string in the project`() = LocalizationTestFixture.withDb {
        val otherStringId = strings.add(LocalizationString(projectId = projectId, key = "other", plural = true)).id
        plurals.upsert(stringId, "en", "one", "x", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        plurals.upsert(otherStringId, "en", "other", "y", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        plurals.upsert(otherStringId, "ar", "one", "z", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)

        val rows = plurals.getByProjectAndLanguage(projectId, "en")
        assertEquals(2, rows.size, "query must return both english rows but no arabic row")
    }

    @Test
    fun `deleting the string cascades to its plural rows`() = LocalizationTestFixture.withDb {
        plurals.upsert(stringId, "en", "one", "1", TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        strings.deleteById(stringId)
        assertTrue(plurals.getByStringAndLanguage(stringId, "en").isEmpty())
    }
}
