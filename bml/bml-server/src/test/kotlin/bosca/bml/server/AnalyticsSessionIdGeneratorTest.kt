package bosca.bml.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsSessionIdGeneratorTest {
    @Test
    fun `generated sessions are unique ULIDs with a current millisecond timestamp`() {
        val before = System.currentTimeMillis()
        val sessions = List(1024) { AnalyticsSessionUlid.generate() }
        val after = System.currentTimeMillis()
        assertEquals(sessions.size, sessions.toSet().size)
        for (session in sessions) {
            assertTrue(Regex("[0-7][0-9A-HJKMNP-TV-Z]{25}").matches(session), session)
            val timestamp = session.take(10).fold(0L) { value, character ->
                value * 32 + "0123456789ABCDEFGHJKMNPQRSTVWXYZ".indexOf(character)
            }
            assertTrue(timestamp in before..after, session)
        }
    }
}
