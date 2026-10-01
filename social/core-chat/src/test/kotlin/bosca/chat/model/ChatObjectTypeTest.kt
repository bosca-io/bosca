package bosca.chat.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatObjectTypeTest {

    @Test
    fun `has all expected values`() {
        val values = ChatObjectType.entries.map { it.name }
        assertTrue(values.contains("METADATA"))
        assertTrue(values.contains("COLLECTION"))
        assertTrue(values.contains("LOCALIZATION_KEY"))
        assertTrue(values.contains("CALENDAR_EVENT"))
        assertTrue(values.contains("FEATURE_FLAG"))
        assertTrue(values.contains("EXPERIMENT"))
        assertEquals(6, values.size)
    }

    @Test
    fun `enum names are uppercase canonical form`() {
        for (value in ChatObjectType.entries) {
            assertEquals(value.name.uppercase(), value.name, "${value.name} should be uppercase")
        }
    }
}
