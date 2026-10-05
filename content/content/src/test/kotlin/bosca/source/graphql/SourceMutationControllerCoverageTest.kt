package bosca.source.graphql

import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.source.model.Source
import bosca.source.model.SourceInput
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

class SourceMutationControllerCoverageTest {

    private val service = mockk<SourceService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = SourceMutationController(service, groupEvaluator)
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

    private fun input(name: String = "src"): SourceInput = SourceInput(
        name = name,
        description = "description",
        configuration = JsonObject(emptyMap())
    )

    @Test
    fun `add verifies admin group and delegates to service`() = runTest {
        val input = input("added")
        val expected = source("added")
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { service.add(input) } returns expected

        val result = controller.add(authentication, input)

        assertEquals(expected, result)
        coVerify(exactly = 1) { service.add(input) }
        verify(exactly = 1) { groupEvaluator.verifyHasAdminGroup(authentication) }
    }

    @Test
    fun `add propagates unauthorized and does not call service`() = runTest {
        val input = input()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.add(authentication, input)
        }

        coVerify(exactly = 0) { service.add(any()) }
    }

    @Test
    fun `edit verifies admin group and delegates to service`() = runTest {
        val id = UUID.random()
        val input = input("edited")
        val expected = source("edited")
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { service.edit(id, input) } returns expected

        val result = controller.edit(authentication, id, input)

        assertEquals(expected, result)
        coVerify(exactly = 1) { service.edit(id, input) }
    }

    @Test
    fun `edit propagates unauthorized and does not call service`() = runTest {
        val id = UUID.random()
        val input = input()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.edit(authentication, id, input)
        }

        coVerify(exactly = 0) { service.edit(any(), any()) }
    }

    @Test
    fun `delete verifies admin group deletes and returns true`() = runTest {
        val id = UUID.random()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { service.delete(id) } returns Unit

        val result = controller.delete(authentication, id)

        assertTrue(result)
        coVerify(exactly = 1) { service.delete(id) }
    }

    @Test
    fun `delete propagates unauthorized and does not call service`() = runTest {
        val id = UUID.random()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.delete(authentication, id)
        }

        coVerify(exactly = 0) { service.delete(any()) }
    }
}
