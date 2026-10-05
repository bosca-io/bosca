package bosca.content.state.service

import bosca.content.state.model.State
import bosca.content.state.model.StateInput
import bosca.content.state.model.WorkflowStateType
import bosca.content.state.repository.StateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class StateServiceImplTest {

    private val repository = mockk<StateRepository>(relaxed = true)
    private val service = StateServiceImpl(repository)

    private val config = JsonObject(emptyMap())

    private val state = State(
        id = "draft",
        name = "Draft",
        description = "Initial draft state",
        type = WorkflowStateType.DRAFT,
        configuration = config,
        jobName = null
    )

    @Test
    fun `getAll delegates to repository`() = runTest {
        coEvery { repository.getAll() } returns listOf(state)

        val result = service.getAll()

        assertEquals(1, result.size)
        assertEquals("draft", result[0].id)
    }

    @Test
    fun `get delegates to repository`() = runTest {
        coEvery { repository.getById("draft") } returns state

        val result = service.get("draft")

        assertNotNull(result)
        assertEquals("Draft", result.name)
    }

    @Test
    fun `add creates state from input and returns it`() = runTest {
        val input = StateInput(
            id = "review",
            name = "Review",
            description = "Review state",
            type = WorkflowStateType.APPROVAL,
            configuration = config,
            jobName = "review-job"
        )
        coEvery { repository.add(any()) } answers {
            firstArg()
        }

        val result = service.add(input)

        assertEquals("review", result.id)
        assertEquals("Review", result.name)
        assertEquals(WorkflowStateType.APPROVAL, result.type)
        assertEquals("review-job", result.jobName)
    }

    @Test
    fun `edit updates existing state from input`() = runTest {
        val input = StateInput(
            id = "draft",
            name = "Updated Draft",
            description = "Updated description",
            type = WorkflowStateType.PENDING,
            configuration = config,
            jobName = "updated-job"
        )
        coEvery { repository.getById("draft") } returns state
        coEvery { repository.update(any()) } answers { firstArg() }

        val result = service.edit("draft", input)

        assertEquals("draft", result.id)
        assertEquals("Updated Draft", result.name)
        assertEquals("Updated description", result.description)
        assertEquals(WorkflowStateType.PENDING, result.type)
        assertEquals("updated-job", result.jobName)
    }

    @Test
    fun `edit throws when state not found`() = runTest {
        val input = StateInput(
            id = "nonexistent",
            name = "N/A",
            description = "N/A",
            type = WorkflowStateType.DRAFT,
            configuration = config
        )
        coEvery { repository.getById("nonexistent") } returns null

        assertFailsWith<NoSuchElementException> {
            service.edit("nonexistent", input)
        }
    }

    @Test
    fun `delete delegates to repository`() = runTest {
        service.delete("draft")

        coVerify { repository.deleteById("draft") }
    }
}
