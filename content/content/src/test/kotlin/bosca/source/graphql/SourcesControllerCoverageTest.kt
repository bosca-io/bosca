package bosca.source.graphql

import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.source.model.Source
import bosca.source.service.SourceService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourcesControllerCoverageTest {

    private val service = mockk<SourceService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = SourcesController(service, groupEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun source(name: String = "src"): Source = Source(
        id = UUID.random(),
        name = name,
        description = "description",
        configuration = JsonObject(emptyMap())
    )

    @Test
    fun `all verifies editor group and returns all sources`() = runTest {
        val expected = listOf(source("one"), source("two"))
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.getAll() } returns expected

        val result = controller.all(authentication)

        assertEquals(expected, result)
        verify(exactly = 1) { groupEvaluator.verifyHasEditorGroup(authentication) }
        coVerify(exactly = 1) { service.getAll() }
    }

    @Test
    fun `all returns empty list from service`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.getAll() } returns emptyList()

        val result = controller.all(authentication)

        assertTrue(result.isEmpty())
        coVerify(exactly = 1) { service.getAll() }
    }

    @Test
    fun `all propagates unauthorized and does not call service`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.all(authentication)
        }

        coVerify(exactly = 0) { service.getAll() }
    }

    @Test
    fun `source verifies editor group and returns source by id`() = runTest {
        val id = UUID.random()
        val expected = source("found")
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.getById(id) } returns expected

        val result = controller.source(authentication, id)

        assertEquals(expected, result)
        verify(exactly = 1) { groupEvaluator.verifyHasEditorGroup(authentication) }
        coVerify(exactly = 1) { service.getById(id) }
    }

    @Test
    fun `source propagates unauthorized and does not call service`() = runTest {
        val id = UUID.random()
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.source(authentication, id)
        }

        coVerify(exactly = 0) { service.getById(any()) }
    }
}
