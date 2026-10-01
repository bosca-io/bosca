package bosca.bible.usx

import kotlin.test.Test
import kotlin.test.assertNotNull

class ComponentContextTest {

    @Test
    fun canBeInstantiated() {
        val ctx = ComponentContext()
        assertNotNull(ctx)
    }
}
