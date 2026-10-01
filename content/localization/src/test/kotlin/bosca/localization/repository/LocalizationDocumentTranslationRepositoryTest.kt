@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.localization.LocalizationTestFixture
import bosca.localization.model.LocalizationProject
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

class LocalizationDocumentTranslationRepositoryTest {

    private lateinit var docs: LocalizationProjectDocumentRepository
    private lateinit var translations: LocalizationDocumentTranslationRepository
    private lateinit var projectId: UUID
    private lateinit var documentId: UUID

    @BeforeTest
    fun setup() {
        LocalizationTestFixture.ensureSchema()
        LocalizationTestFixture.reset()
        LocalizationTestFixture.seedLanguage("en")
        LocalizationTestFixture.seedLanguage("es")
        docs = LocalizationProjectDocumentRepositoryImpl()
        translations = LocalizationDocumentTranslationRepositoryImpl()
        LocalizationTestFixture.withDb {
            projectId = LocalizationProjectRepositoryImpl().add(LocalizationProject(name = "P", sourceLanguage = "en")).id
            documentId = docs.upsert(projectId, UUID.random(), null).id
        }
    }

    @AfterTest
    fun tearDown() {
        LocalizationTestFixture.reset()
    }

    @Test
    fun `upsert round-trips JSONB content verbatim`() = LocalizationTestFixture.withDb {
        val content = buildJsonObject {
            put("type", JsonPrimitive("doc"))
            put("title", JsonPrimitive("Hola"))
        }
        val saved = translations.upsert(documentId, "es", content, TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        val fetched = translations.getById(saved.id)!!
        assertEquals(content, fetched.content)
    }

    @Test
    fun `upsert on (documentId, languageTag) conflict replaces content and state`() = LocalizationTestFixture.withDb {
        val first = translations.upsert(documentId, "es", buildJsonObject { put("v", JsonPrimitive(1)) }, TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        val second = translations.upsert(documentId, "es", buildJsonObject { put("v", JsonPrimitive(2)) }, TranslationState.PUBLISHED, TranslationOrigin.SYNC, "crowdin", null)
        assertEquals(first.id, second.id)
        assertEquals(TranslationState.PUBLISHED, second.state)
        assertEquals(TranslationOrigin.SYNC, second.origin)
    }

    @Test
    fun `different languages do not conflict on the same document`() = LocalizationTestFixture.withDb {
        translations.upsert(documentId, "en", buildJsonObject { put("t", JsonPrimitive("en")) }, TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        translations.upsert(documentId, "es", buildJsonObject { put("t", JsonPrimitive("es")) }, TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        val all = translations.getByDocumentId(documentId)
        assertEquals(2, all.size)
    }

    @Test
    fun `getByDocumentAndLanguage returns null when absent, populated when present`() = LocalizationTestFixture.withDb {
        assertNull(translations.getByDocumentAndLanguage(documentId, "es"))
        translations.upsert(documentId, "es", buildJsonObject { put("t", JsonPrimitive("hi")) }, TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        assertNotNull(translations.getByDocumentAndLanguage(documentId, "es"))
    }

    @Test
    fun `transitionStateWithReviewer records reviewer and bumps reviewed_at`() = LocalizationTestFixture.withDb {
        val inserted = translations.upsert(documentId, "es", buildJsonObject { put("t", JsonPrimitive("x")) }, TranslationState.IN_REVIEW, TranslationOrigin.HUMAN, null, null)
        val reviewer = UUID.random()
        val approved = translations.transitionStateWithReviewer(inserted.id, TranslationState.APPROVED, reviewer)!!
        assertEquals(TranslationState.APPROVED, approved.state)
        assertEquals(reviewer, approved.reviewedBy)
        assertNotNull(approved.reviewedAt)
    }

    @Test
    fun `transitionStateWithoutReviewer leaves the previous reviewer intact`() = LocalizationTestFixture.withDb {
        val inserted = translations.upsert(documentId, "es", buildJsonObject { put("t", JsonPrimitive("x")) }, TranslationState.IN_REVIEW, TranslationOrigin.HUMAN, null, null)
        val reviewer = UUID.random()
        translations.transitionStateWithReviewer(inserted.id, TranslationState.APPROVED, reviewer)
        val published = translations.transitionStateWithoutReviewer(inserted.id, TranslationState.PUBLISHED)!!
        assertEquals(reviewer, published.reviewedBy)
        assertEquals(TranslationState.PUBLISHED, published.state)
    }

    @Test
    fun `deleting the parent document link cascades to its translations`() = LocalizationTestFixture.withDb {
        translations.upsert(documentId, "es", buildJsonObject { put("t", JsonPrimitive("x")) }, TranslationState.DRAFT, TranslationOrigin.HUMAN, null, null)
        docs.deleteByProjectAndMetadata(projectId, docs.getById(documentId)!!.metadataId)
        assertTrue(translations.getByDocumentId(documentId).isEmpty())
    }
}
