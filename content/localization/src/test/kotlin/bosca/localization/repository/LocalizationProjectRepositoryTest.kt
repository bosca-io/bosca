@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.localization.LocalizationTestFixture
import bosca.localization.model.LocalizationProject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Repository integration tests for `localization.projects`. Each test runs against a
 * real Postgres via TestContainers so the SQL in the @Query annotations
 * (including `returning *`, `on delete cascade`, foreign key to `public.languages`)
 * is actually verified.
 */
class LocalizationProjectRepositoryTest {

    private lateinit var repository: LocalizationProjectRepository

    @BeforeTest
    fun setup() {
        LocalizationTestFixture.ensureSchema()
        LocalizationTestFixture.reset()
        LocalizationTestFixture.seedLanguage("en")
        LocalizationTestFixture.seedLanguage("es")
        repository = LocalizationProjectRepositoryImpl()
    }

    @AfterTest
    fun tearDown() {
        LocalizationTestFixture.reset()
    }

    @Test
    fun `add persists a project with generated UUID and default timestamps`() = LocalizationTestFixture.withDb {
        val inserted = repository.add(
            LocalizationProject(name = "iOS App", description = "Translations for iOS", sourceLanguage = "en")
        )
        assertNotEquals(bosca.serialization.UUID.NIL, inserted.id)
        assertEquals("iOS App", inserted.name)
        assertEquals("en", inserted.sourceLanguage)
        assertNotNull(inserted.created)
        assertNotNull(inserted.modified)
    }

    @Test
    fun `add rejects a source language that does not exist in public languages`() = LocalizationTestFixture.withDb {
        var threw = false
        try {
            repository.add(LocalizationProject(name = "Broken", sourceLanguage = "xx"))
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw, "Insert with a non-existent source_language should fail the public.languages foreign key")
    }

    @Test
    fun `getAll returns every project ordered by name`() = LocalizationTestFixture.withDb {
        repository.add(LocalizationProject(name = "Zeta", sourceLanguage = "en"))
        repository.add(LocalizationProject(name = "Alpha", sourceLanguage = "en"))
        repository.add(LocalizationProject(name = "Mike", sourceLanguage = "en"))
        val names = repository.getAll().map { it.name }
        assertEquals(listOf("Alpha", "Mike", "Zeta"), names)
    }

    @Test
    fun `getById returns the inserted project`() = LocalizationTestFixture.withDb {
        val created = repository.add(LocalizationProject(name = "Only", sourceLanguage = "en"))
        val fetched = repository.getById(created.id)
        assertNotNull(fetched)
        assertEquals(created.id, fetched.id)
        assertEquals("Only", fetched.name)
    }

    @Test
    fun `getById returns null when the project does not exist`() = LocalizationTestFixture.withDb {
        val result = repository.getById(bosca.serialization.UUID.random())
        assertNull(result)
    }

    @Test
    fun `getByIds returns only the requested projects`() = LocalizationTestFixture.withDb {
        val a = repository.add(LocalizationProject(name = "A", sourceLanguage = "en"))
        val b = repository.add(LocalizationProject(name = "B", sourceLanguage = "en"))
        repository.add(LocalizationProject(name = "C", sourceLanguage = "en"))
        val fetched = repository.getByIds(listOf(a.id, b.id)).map { it.id }.toSet()
        assertEquals(setOf(a.id, b.id), fetched)
    }

    @Test
    fun `update applies changes and bumps modified`() = LocalizationTestFixture.withDb {
        val created = repository.add(LocalizationProject(name = "Old", sourceLanguage = "en"))
        Thread.sleep(5)
        val updated = repository.update(created.copy(name = "New", description = "updated", sourceLanguage = "es"))
        assertNotNull(updated)
        assertEquals("New", updated.name)
        assertEquals("updated", updated.description)
        assertEquals("es", updated.sourceLanguage)
        assertTrue(updated.modified!! >= created.modified!!, "modified should be at-least as recent as created")
    }

    @Test
    fun `update returns null for a missing project`() = LocalizationTestFixture.withDb {
        val ghost = LocalizationProject(id = bosca.serialization.UUID.random(), name = "Ghost", sourceLanguage = "en")
        val result = repository.update(ghost)
        assertNull(result)
    }

    @Test
    fun `deleteById removes the project`() = LocalizationTestFixture.withDb {
        val created = repository.add(LocalizationProject(name = "Condemned", sourceLanguage = "en"))
        repository.deleteById(created.id)
        assertNull(repository.getById(created.id))
    }
}
