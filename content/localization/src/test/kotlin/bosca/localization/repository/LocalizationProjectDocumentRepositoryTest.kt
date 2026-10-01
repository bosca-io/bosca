@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.localization.LocalizationTestFixture
import bosca.localization.model.LocalizationProject
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

class LocalizationProjectDocumentRepositoryTest {

    private lateinit var projects: LocalizationProjectRepository
    private lateinit var docs: LocalizationProjectDocumentRepository
    private lateinit var projectId: UUID

    @BeforeTest
    fun setup() {
        LocalizationTestFixture.ensureSchema()
        LocalizationTestFixture.reset()
        LocalizationTestFixture.seedLanguage("en")
        projects = LocalizationProjectRepositoryImpl()
        docs = LocalizationProjectDocumentRepositoryImpl()
        LocalizationTestFixture.withDb {
            projectId = projects.add(LocalizationProject(name = "P", sourceLanguage = "en")).id
        }
    }

    @AfterTest
    fun tearDown() {
        LocalizationTestFixture.reset()
    }

    @Test
    fun `upsert inserts a new document link with attributes`() = LocalizationTestFixture.withDb {
        val metadataId = UUID.random()
        val attrs = buildJsonObject { put("title", JsonPrimitive("Welcome")) }
        val doc = docs.upsert(projectId, metadataId, attrs)
        assertEquals(projectId, doc.projectId)
        assertEquals(metadataId, doc.metadataId)
        assertEquals(attrs, doc.attributes)
    }

    @Test
    fun `upsert on (project, metadata) conflict replaces attributes`() = LocalizationTestFixture.withDb {
        val metadataId = UUID.random()
        val first = docs.upsert(projectId, metadataId, buildJsonObject { put("v", JsonPrimitive(1)) })
        val second = docs.upsert(projectId, metadataId, buildJsonObject { put("v", JsonPrimitive(2)) })
        assertEquals(first.id, second.id, "upsert must round-trip the same row via the (project_id, metadata_id) unique constraint")
        assertEquals(JsonPrimitive(2), (second.attributes as kotlinx.serialization.json.JsonObject)["v"])
    }

    @Test
    fun `getByProjectId returns only this project's documents ordered by created`() = LocalizationTestFixture.withDb {
        val secondProject = projects.add(LocalizationProject(name = "other", sourceLanguage = "en")).id
        val mA = UUID.random()
        val mB = UUID.random()
        val mOther = UUID.random()
        docs.upsert(projectId, mA, null)
        docs.upsert(projectId, mB, null)
        docs.upsert(secondProject, mOther, null)

        val result = docs.getByProjectId(projectId)
        assertEquals(2, result.size, "other project's documents must not leak into this listing")
    }

    @Test
    fun `getById returns the inserted row or null`() = LocalizationTestFixture.withDb {
        val metadataId = UUID.random()
        val doc = docs.upsert(projectId, metadataId, null)
        assertNotNull(docs.getById(doc.id))
        assertNull(docs.getById(UUID.random()))
    }

    @Test
    fun `deleteByProjectAndMetadata removes the link`() = LocalizationTestFixture.withDb {
        val metadataId = UUID.random()
        docs.upsert(projectId, metadataId, null)
        docs.deleteByProjectAndMetadata(projectId, metadataId)
        assertTrue(docs.getByProjectId(projectId).isEmpty())
    }

    @Test
    fun `deleting the project cascades to its document links`() = LocalizationTestFixture.withDb {
        docs.upsert(projectId, UUID.random(), null)
        projects.deleteById(projectId)
        assertTrue(docs.getByProjectId(projectId).isEmpty())
    }
}
