package bosca.bible

import kotlin.test.Test
import kotlin.test.assertEquals

class BibleSystemTest {

    @Test
    fun fieldPreservation() {
        val system = BibleSystem(id = "paratext-123")
        assertEquals("paratext-123", system.id)
    }

    @Test
    fun asSerializableReturnsSameValues() {
        val system = BibleSystem(id = "sys-1")
        val serializable = system.asSerializable()
        assertEquals("sys-1", serializable.id)
    }

    @Test
    fun interfaceAsSerializableProducesCorrectType() {
        val system: IBibleSystem = BibleSystem(id = "test")
        val result = system.asSerializable()
        assertEquals("test", result.id)
    }
}
