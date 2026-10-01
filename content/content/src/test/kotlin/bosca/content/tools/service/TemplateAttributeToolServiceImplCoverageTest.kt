package bosca.content.tools.service

import bosca.content.tools.model.TemplateAttributeTool
import bosca.content.tools.model.TemplateAttributeToolInput
import bosca.content.tools.repository.TemplateAttributeToolRepository
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TemplateAttributeToolServiceImplCoverageTest {

    private val repository = mockk<TemplateAttributeToolRepository>()
    private val service = TemplateAttributeToolServiceImpl(repository)

    @AfterTest
    fun teardown() {
        unmockkAll()
    }

    private fun tool(id: UUID) = TemplateAttributeTool(
        id = id,
        key = "k",
        name = "n",
        description = "d",
        query = "q",
        resultPath = "r",
        configuration = JsonPrimitive("c"),
    )

    @Test
    fun `getAll delegates to repository`() = runTest {
        val expected = listOf(tool(UUID.random()), tool(UUID.random()))
        coEvery { repository.getAll() } returns expected

        val result = service.getAll()

        assertSame(expected, result)
        coVerify(exactly = 1) { repository.getAll() }
    }

    @Test
    fun `getAll returns empty list when repository has none`() = runTest {
        coEvery { repository.getAll() } returns emptyList()

        val result = service.getAll()

        assertEquals(emptyList(), result)
    }

    @Test
    fun `get delegates to repository and returns the tool`() = runTest {
        val id = UUID.random()
        val expected = tool(id)
        coEvery { repository.get(id) } returns expected

        val result = service.get(id)

        assertSame(expected, result)
        coVerify(exactly = 1) { repository.get(id) }
    }

    @Test
    fun `get returns null when repository has no match`() = runTest {
        val id = UUID.random()
        coEvery { repository.get(id) } returns null

        val result = service.get(id)

        assertNull(result)
    }

    @Test
    fun `add maps all input fields and generates a random id`() = runTest {
        val input = TemplateAttributeToolInput(
            key = "the-key",
            name = "the-name",
            description = "the-description",
            query = "the-query",
            resultPath = "the-result-path",
            configuration = JsonPrimitive("the-config"),
        )
        val captured = slot<TemplateAttributeTool>()
        val returned = tool(UUID.random())
        coEvery { repository.add(capture(captured)) } returns returned

        val result = service.add(input)

        assertSame(returned, result)
        val built = captured.captured
        assertEquals(input.key, built.key)
        assertEquals(input.name, built.name)
        assertEquals(input.description, built.description)
        assertEquals(input.query, built.query)
        assertEquals(input.resultPath, built.resultPath)
        assertEquals(input.configuration, built.configuration)
        // A fresh random id is minted for adds.
        assertNotEquals(returned.id, built.id)
        coVerify(exactly = 1) { repository.add(any()) }
    }

    @Test
    fun `add generates distinct ids across invocations`() = runTest {
        val input = TemplateAttributeToolInput(key = "k", name = "n", query = "q")
        val captured = mutableListOf<TemplateAttributeTool>()
        coEvery { repository.add(capture(captured)) } answers { firstArg() }

        service.add(input)
        service.add(input)

        assertNotEquals(captured[0].id, captured[1].id)
    }

    @Test
    fun `add handles null optional fields`() = runTest {
        val input = TemplateAttributeToolInput(
            key = "k",
            name = "n",
            description = null,
            query = "q",
            resultPath = null,
            configuration = null,
        )
        val captured = slot<TemplateAttributeTool>()
        coEvery { repository.add(capture(captured)) } answers { firstArg() }

        service.add(input)

        val built = captured.captured
        assertNull(built.description)
        assertNull(built.resultPath)
        assertNull(built.configuration)
    }

    @Test
    fun `edit preserves the supplied id and maps all fields`() = runTest {
        val id = UUID.random()
        val input = TemplateAttributeToolInput(
            key = "edited-key",
            name = "edited-name",
            description = "edited-description",
            query = "edited-query",
            resultPath = "edited-result-path",
            configuration = JsonPrimitive("edited-config"),
        )
        val captured = slot<TemplateAttributeTool>()
        val returned = tool(id)
        coEvery { repository.edit(capture(captured)) } returns returned

        val result = service.edit(id, input)

        assertSame(returned, result)
        val built = captured.captured
        assertEquals(id, built.id)
        assertEquals(input.key, built.key)
        assertEquals(input.name, built.name)
        assertEquals(input.description, built.description)
        assertEquals(input.query, built.query)
        assertEquals(input.resultPath, built.resultPath)
        assertEquals(input.configuration, built.configuration)
        coVerify(exactly = 1) { repository.edit(any()) }
    }

    @Test
    fun `edit handles null optional fields`() = runTest {
        val id = UUID.random()
        val input = TemplateAttributeToolInput(
            key = "k",
            name = "n",
            description = null,
            query = "q",
            resultPath = null,
            configuration = null,
        )
        val captured = slot<TemplateAttributeTool>()
        coEvery { repository.edit(capture(captured)) } answers { firstArg() }

        service.edit(id, input)

        val built = captured.captured
        assertEquals(id, built.id)
        assertNull(built.description)
        assertNull(built.resultPath)
        assertNull(built.configuration)
    }

    @Test
    fun `delete delegates to repository`() = runTest {
        val id = UUID.random()
        coEvery { repository.delete(id) } just Runs

        service.delete(id)

        coVerify(exactly = 1) { repository.delete(id) }
    }
}
