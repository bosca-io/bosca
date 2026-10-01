package bosca.segmentation.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class BannerWeightTest {

    @Test
    fun `BannerWeight stores id and weight`() {
        val id = UUID.random()
        val bw = BannerWeight(id = id, weight = 42)
        assertEquals(id, bw.id)
        assertEquals(42, bw.weight)
    }

    @Test
    fun `BannerWeight equality is based on id and weight`() {
        val id = UUID.random()
        val a = BannerWeight(id = id, weight = 10)
        val b = BannerWeight(id = id, weight = 10)
        assertEquals(a, b)
    }
}
