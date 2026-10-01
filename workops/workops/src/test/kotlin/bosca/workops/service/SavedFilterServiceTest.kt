package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsConflictException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.bql.BqlParseException
import bosca.workops.model.bql.SavedFilter
import bosca.workops.model.bql.SavedFilterInput
import bosca.workops.repository.SavedFilterRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.Runs
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SavedFilterServiceTest {

    private val repository = mockk<SavedFilterRepository>()
    private val service = SavedFilterServiceImpl(repository, Json)
    private val ownerId = UUID.random()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `reads clamp pagination and avoid an empty batch query`() = runTest {
        val saved = savedFilter()
        coEvery { repository.listByOwner(ownerId, 0, 200) } returns listOf(saved)
        coEvery { repository.getById(saved.id) } returns saved
        coEvery { repository.getByIds(listOf(saved.id)) } returns listOf(saved)

        assertEquals(listOf(saved), service.listForOwner(ownerId, -20, 900))
        assertSame(saved, service.getById(saved.id))
        assertEquals(emptyList(), service.getByIds(emptyList()))
        assertEquals(listOf(saved), service.getByIds(listOf(saved.id)))
        coVerify(exactly = 1) { repository.getByIds(any()) }
    }

    @Test
    fun `create validates names syntax semantics uniqueness and persists the parsed query`() = runTest {
        assertFailsWith<WorkOpsValidationException> {
            service.create(ownerId, SavedFilterInput(" ", bqlSource = "summary = \"x\""))
        }
        assertFailsWith<BqlParseException> {
            service.create(ownerId, SavedFilterInput("Malformed", bqlSource = "==="))
        }
        assertFailsWith<BqlParseException> {
            service.create(ownerId, SavedFilterInput("Unknown", bqlSource = "unknown = value"))
        }

        val duplicate = savedFilter(name = "Mine")
        coEvery { repository.listByOwner(ownerId, 0, 200) } returns listOf(duplicate)
        assertFailsWith<WorkOpsConflictException> {
            service.create(ownerId, SavedFilterInput("Mine", bqlSource = "summary = \"x\""))
        }

        val captured = slot<SavedFilter>()
        val persisted = savedFilter(name = "Open", source = "status = Open")
        coEvery { repository.listByOwner(ownerId, 0, 200) } returns emptyList()
        coEvery { repository.add(capture(captured)) } returns persisted

        assertSame(
            persisted,
            service.create(ownerId, SavedFilterInput("Open", "Work in progress", "status = Open")),
        )
        assertEquals(ownerId, captured.captured.ownerProfileId)
        assertEquals("Open", captured.captured.name)
        assertEquals("Work in progress", captured.captured.description)
        assertEquals("status = Open", captured.captured.bqlSource)
    }

    @Test
    fun `update reports missing and stale rows and writes the requested version`() = runTest {
        val id = UUID.random()
        val input = SavedFilterInput("Updated", "Changed", "summary ~ \"release\"")
        coEvery { repository.getById(id) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.update(id, input, 4) }

        val existing = savedFilter(id = id)
        val captured = slot<SavedFilter>()
        coEvery { repository.getById(id) } returns existing
        coEvery { repository.update(capture(captured)) } returns null
        assertFailsWith<OptimisticLockFailedException> { service.update(id, input, 4) }
        assertEquals(4, captured.captured.version)
        assertEquals("Updated", captured.captured.name)
        assertEquals("Changed", captured.captured.description)

        val updated = captured.captured.copy(version = 5)
        coEvery { repository.update(any()) } returns updated
        assertSame(updated, service.update(id, input, 4))
        coEvery { repository.deleteById(id) } just Runs
        service.delete(id)
        coVerify(exactly = 1) { repository.deleteById(id) }
    }

    private fun savedFilter(
        id: UUID = UUID.random(),
        name: String = "Mine",
        source: String = "summary = \"mine\"",
    ) = SavedFilter(
        id = id,
        ownerProfileId = ownerId,
        name = name,
        bqlSource = source,
        parsedAst = JsonObject(emptyMap()),
    )
}
