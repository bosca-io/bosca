package bosca.content.timeevent.graphql

import bosca.content.attributes.model.TemplateAttribute
import bosca.content.timeevent.model.TimeEventType
import bosca.content.timeevent.service.TimeEventService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Coverage for [TimeEventTypeController.attributes], the one resolver not
 * exercised by [TimeEventTypeControllerTest]. It delegates to
 * [TimeEventService.getTypeAttributes] with the type's id.
 */
class TimeEventTypeControllerCoverageTest {

    private val timeEventService = mockk<TimeEventService>()
    private val controller = TimeEventTypeController(timeEventService)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createType(id: String = "chapter") = TimeEventType(
        id = id,
        name = "Chapter",
        description = "Chapter marker"
    )

    @Test
    fun `attributes returns service result for the type id`() = runTest {
        val type = createType(id = "chapter")
        val expected = listOf(TemplateAttribute(), TemplateAttribute())

        coEvery { timeEventService.getTypeAttributes("chapter") } returns expected

        val result = controller.attributes(type)

        assertSame(expected, result)
        assertEquals(2, result.size)
    }

    @Test
    fun `attributes returns empty list when type has no attributes`() = runTest {
        val type = createType(id = "caption")

        coEvery { timeEventService.getTypeAttributes("caption") } returns emptyList()

        val result = controller.attributes(type)

        assertTrue(result.isEmpty())
    }
}
