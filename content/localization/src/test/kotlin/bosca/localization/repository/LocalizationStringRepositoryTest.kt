@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.localization.LocalizationTestFixture
import bosca.localization.model.LocalizationProject
import bosca.localization.model.LocalizationString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.uuid.ExperimentalUuidApi

/**
 * Integration tests for `localization.strings`. These lock in the SQL behaviour that
 * the service layer relies on: the varchar[] tags array round-trips, JSONB
 * placeholders survive the database roundtrip, and the `(project_id, key)`
 * unique index is real.
 */
class LocalizationStringRepositoryTest {

    private lateinit var projects: LocalizationProjectRepository
    private lateinit var strings: LocalizationStringRepository
    private lateinit var projectId: bosca.serialization.UUID

    @BeforeTest
    fun setup() {
        LocalizationTestFixture.ensureSchema()
        LocalizationTestFixture.reset()
        LocalizationTestFixture.seedLanguage("en")
        projects = LocalizationProjectRepositoryImpl()
        strings = LocalizationStringRepositoryImpl()
        LocalizationTestFixture.withDb {
            projectId = projects.add(LocalizationProject(name = "Test", sourceLanguage = "en")).id
        }
    }

    @AfterTest
    fun tearDown() {
        LocalizationTestFixture.reset()
    }

    @Test
    fun `add persists string with tags and plural flag, modifying neither on read`() = LocalizationTestFixture.withDb {
        val saved = strings.add(
            LocalizationString(
                projectId = projectId,
                key = "items_count",
                context = "Number of items displayed in the cart badge",
                tags = listOf("nav", "cart"),
                plural = true
            )
        )
        val fetched = strings.getById(saved.id)!!
        assertEquals("items_count", fetched.key)
        assertEquals(listOf("nav", "cart"), fetched.tags)
        assertTrue(fetched.plural)
        assertEquals("Number of items displayed in the cart badge", fetched.context)
    }

    @Test
    fun `add round-trips JSONB placeholders verbatim`() = LocalizationTestFixture.withDb {
        val placeholders = JsonArray(listOf(
            kotlinx.serialization.json.buildJsonObject {
                put("name", JsonPrimitive("count"))
                put("type", JsonPrimitive("number"))
            },
            kotlinx.serialization.json.buildJsonObject {
                put("name", JsonPrimitive("userName"))
                put("type", JsonPrimitive("string"))
            }
        ))
        val saved = strings.add(
            LocalizationString(projectId = projectId, key = "greet", placeholders = placeholders)
        )
        val fetched = strings.getById(saved.id)!!
        assertEquals(placeholders, fetched.placeholders)
    }

    @Test
    fun `duplicate (project_id, key) rejected by unique index`() = LocalizationTestFixture.withDb {
        strings.add(LocalizationString(projectId = projectId, key = "dup"))
        var threw = false
        try {
            strings.add(LocalizationString(projectId = projectId, key = "dup"))
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw, "Inserting a second row with the same (project_id, key) pair must violate the unique constraint")
    }

    @Test
    fun `same key in a different project is allowed`() = LocalizationTestFixture.withDb {
        val secondProjectId = projects.add(LocalizationProject(name = "Second", sourceLanguage = "en")).id
        strings.add(LocalizationString(projectId = projectId, key = "shared"))
        // Must not throw:
        strings.add(LocalizationString(projectId = secondProjectId, key = "shared"))
    }

    @Test
    fun `getByKey finds by project-scoped lookup`() = LocalizationTestFixture.withDb {
        strings.add(LocalizationString(projectId = projectId, key = "welcome"))
        val found = strings.getByKey(projectId, "welcome")
        assertNotNull(found)
        assertEquals("welcome", found.key)
        assertNull(strings.getByKey(projectId, "missing"))
    }

    @Test
    fun `getByProjectId returns keys ordered alphabetically with paging`() = LocalizationTestFixture.withDb {
        strings.add(LocalizationString(projectId = projectId, key = "zzz"))
        strings.add(LocalizationString(projectId = projectId, key = "aaa"))
        strings.add(LocalizationString(projectId = projectId, key = "mmm"))
        val firstTwo = strings.getByProjectId(projectId, offset = 0, limit = 2).map { it.key }
        assertEquals(listOf("aaa", "mmm"), firstTwo)
        val nextPage = strings.getByProjectId(projectId, offset = 2, limit = 2).map { it.key }
        assertEquals(listOf("zzz"), nextPage)
    }

    @Test
    fun `countByProjectId reflects inserts and deletes`() = LocalizationTestFixture.withDb {
        assertEquals(0, strings.countByProjectId(projectId))
        val a = strings.add(LocalizationString(projectId = projectId, key = "a"))
        strings.add(LocalizationString(projectId = projectId, key = "b"))
        assertEquals(2, strings.countByProjectId(projectId))
        strings.deleteById(a.id)
        assertEquals(1, strings.countByProjectId(projectId))
    }

    @Test
    fun `update changes key, context, placeholders, tags, plural flag`() = LocalizationTestFixture.withDb {
        val saved = strings.add(LocalizationString(projectId = projectId, key = "old", plural = false))
        val updated = strings.update(saved.copy(key = "new", context = "x", tags = listOf("t1"), plural = true))
        assertNotNull(updated)
        assertEquals("new", updated.key)
        assertEquals("x", updated.context)
        assertEquals(listOf("t1"), updated.tags)
        assertTrue(updated.plural)
    }

    @Test
    fun `update returns null for a missing id`() = LocalizationTestFixture.withDb {
        val phantom = LocalizationString(id = bosca.serialization.UUID.random(), projectId = projectId, key = "x")
        assertNull(strings.update(phantom))
    }

    @Test
    fun `deleteById clears the row and countByProjectId sees it gone`() = LocalizationTestFixture.withDb {
        val saved = strings.add(LocalizationString(projectId = projectId, key = "bye"))
        strings.deleteById(saved.id)
        assertNull(strings.getById(saved.id))
        assertFalse(strings.getByProjectId(projectId, 0, 100).any { it.id == saved.id })
    }

    @Test
    fun `deleting a project cascades to its strings`() = LocalizationTestFixture.withDb {
        strings.add(LocalizationString(projectId = projectId, key = "temp"))
        projects.deleteById(projectId)
        assertEquals(0, strings.countByProjectId(projectId))
    }
}
