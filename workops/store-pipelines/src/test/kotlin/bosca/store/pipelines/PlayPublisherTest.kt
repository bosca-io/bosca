package bosca.store.pipelines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayPublisherTest {

    @Test
    fun `release percentages convert to Play fractions at one checked boundary`() {
        assertEquals(0.0, playUserFraction(0.0))
        assertEquals(0.375, playUserFraction(37.5))
        assertEquals(1.0, playUserFraction(100.0))

        listOf(-0.1, 100.1, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertFailsWith<IllegalArgumentException> { playUserFraction(it) }
        }
    }

}
