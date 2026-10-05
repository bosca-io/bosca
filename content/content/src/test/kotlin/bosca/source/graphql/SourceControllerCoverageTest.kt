package bosca.source.graphql

import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.source.model.Source
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SourceControllerCoverageTest {

    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = SourceController(groupEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun source(
        id: UUID = UUID.random(),
        name: String = "src",
        description: String = "description",
        configuration: JsonObject = JsonObject(emptyMap())
    ): Source = Source(
        id = id,
        name = name,
        description = description,
        configuration = configuration
    )

    @Test
    fun `id returns source id`() {
        val id = UUID.random()
        val source = source(id = id)

        assertEquals(id, controller.id(source))
    }

    @Test
    fun `name returns source name`() {
        val source = source(name = "the-name")

        assertEquals("the-name", controller.name(source))
    }

    @Test
    fun `description returns source description`() {
        val source = source(description = "the-description")

        assertEquals("the-description", controller.description(source))
    }

    @Test
    fun `configuration verifies sa group and returns configuration`() {
        val configuration = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val source = source(configuration = configuration)
        every { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit

        val result = controller.configuration(authentication, source)

        assertSame(configuration, result)
        verify(exactly = 1) { groupEvaluator.verifyHasSaGroup(authentication) }
    }

    @Test
    fun `configuration propagates unauthorized`() {
        val source = source()
        every { groupEvaluator.verifyHasSaGroup(authentication) } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.configuration(authentication, source)
        }

        verify(exactly = 1) { groupEvaluator.verifyHasSaGroup(authentication) }
    }
}
