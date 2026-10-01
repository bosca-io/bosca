package bosca.chat.model

import kotlin.test.Test
import kotlin.test.assertTrue

class ChatChannelTypeTest {
    @Test
    fun hasExpectedValues() {
        val values = ChatChannelType.entries.map { it.name }
        assertTrue(values.contains("DIRECT"))
        assertTrue(values.contains("GROUP"))
        assertTrue(values.contains("PUBLIC"))
    }
}
