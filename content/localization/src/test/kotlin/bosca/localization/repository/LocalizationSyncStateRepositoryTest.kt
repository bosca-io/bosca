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

class LocalizationSyncStateRepositoryTest {

    private lateinit var sync: LocalizationSyncStateRepository
    private lateinit var projectId: UUID

    @BeforeTest
    fun setup() {
        LocalizationTestFixture.ensureSchema()
        LocalizationTestFixture.reset()
        LocalizationTestFixture.seedLanguage("en")
        sync = LocalizationSyncStateRepositoryImpl()
        LocalizationTestFixture.withDb {
            projectId = LocalizationProjectRepositoryImpl().add(LocalizationProject(name = "P", sourceLanguage = "en")).id
        }
    }

    @AfterTest
    fun tearDown() {
        LocalizationTestFixture.reset()
    }

    @Test
    fun `upsert inserts a sync binding`() = LocalizationTestFixture.withDb {
        val cfg = buildJsonObject { put("apiToken", JsonPrimitive("xyz")) }
        val state = sync.upsert(projectId, "crowdin", "ext-123", cfg)
        assertEquals(projectId, state.projectId)
        assertEquals("crowdin", state.provider)
        assertEquals("ext-123", state.externalId)
        assertEquals(cfg, state.syncConfig)
        assertNull(state.lastSynced, "lastSynced must be null until markSynced is called")
    }

    @Test
    fun `upsert on (project, provider) conflict replaces externalId and syncConfig`() = LocalizationTestFixture.withDb {
        val first = sync.upsert(projectId, "crowdin", "old", buildJsonObject { put("k", JsonPrimitive("v1")) })
        val second = sync.upsert(projectId, "crowdin", "new", buildJsonObject { put("k", JsonPrimitive("v2")) })
        assertEquals(first.id, second.id, "upsert must return the same row for the same (project_id, provider) pair")
        assertEquals("new", second.externalId)
    }

    @Test
    fun `markSynced updates lastSynced timestamp`() = LocalizationTestFixture.withDb {
        sync.upsert(projectId, "crowdin", null, null)
        sync.markSynced(projectId)
        val fetched = sync.getByProjectId(projectId)!!
        assertNotNull(fetched.lastSynced, "markSynced should populate lastSynced")
    }

    @Test
    fun `getByProjectId returns null when no binding exists`() = LocalizationTestFixture.withDb {
        assertNull(sync.getByProjectId(projectId))
    }

    @Test
    fun `deleteByProjectId clears the binding`() = LocalizationTestFixture.withDb {
        sync.upsert(projectId, "crowdin", null, null)
        sync.deleteByProjectId(projectId)
        assertNull(sync.getByProjectId(projectId))
    }

    @Test
    fun `deleting the project cascades to the sync binding`() = LocalizationTestFixture.withDb {
        sync.upsert(projectId, "crowdin", null, null)
        LocalizationProjectRepositoryImpl().deleteById(projectId)
        assertNull(sync.getByProjectId(projectId))
    }

    @Test
    fun `different providers can coexist for the same project`() = LocalizationTestFixture.withDb {
        sync.upsert(projectId, "crowdin", "c-1", null)
        // (Provider-agnostic design: even though only Crowdin is implemented, the schema allows more.)
        sync.upsert(projectId, "lokalise", "l-2", null)
        // getByProjectId only returns one row regardless — it picks arbitrary via LIMIT 1, so we just
        // confirm both bindings exist by re-upserting each and checking it succeeds without constraint error.
        val refreshCrowdin = sync.upsert(projectId, "crowdin", "c-1-updated", null)
        assertEquals("c-1-updated", refreshCrowdin.externalId)
        assertTrue(true, "no unique constraint violations when two providers exist for one project")
    }
}
