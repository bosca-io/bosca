package bosca.bml.render

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BmlIslandActionDispatcherTest {

    @Test
    fun `page session keys stay collision-free and cache-safe`() {
        assertNotEquals(
            bmlPageSessionStateKey("/a", "_state"),
            bmlPageSessionStateKey("/a_", "state"),
        )
        assertTrue(bmlPageSessionStateKey("/items/a:b", "row.count:one").matches(Regex("[A-Za-z0-9._-]+")))
    }
}
