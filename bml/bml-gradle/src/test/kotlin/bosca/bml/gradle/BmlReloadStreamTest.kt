package bosca.bml.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BmlReloadStreamTest {
    @Test
    fun `heartbeats and unchanged tokens are ignored between application generations`() {
        val reader = ("data: boot:0\n\n" +
            "event: ping\ndata:\n\n" +
            "event: ping\ndata: heartbeat\n\n" +
            ": keepalive\n\n" +
            "data: boot:0\n\n" +
            "data: boot:1\n\n").reader().buffered()
        val initial = readReloadToken(reader)
        assertEquals("boot:0", initial)
        assertEquals("boot:1", readReloadToken(reader, initial))
    }

    @Test
    fun `closed streams fail instead of returning a heartbeat`() {
        assertFailsWith<IllegalArgumentException> {
            readReloadToken("event: ping\ndata:\n\n".reader().buffered())
        }
    }
}
