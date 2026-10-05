package bosca.segmentation.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampaignInputTest {

    @Test
    fun `CampaignInput defaults placement to null`() {
        val input = CampaignInput(
            name = "test",
            channel = NotificationChannel.BANNER,
            segmentIds = emptyList()
        )
        assertNull(input.placement)
    }

    @Test
    fun `CampaignInput defaults weight to 0`() {
        val input = CampaignInput(
            name = "test",
            channel = NotificationChannel.BANNER,
            segmentIds = emptyList()
        )
        assertEquals(0, input.weight)
    }

    @Test
    fun `CampaignInput stores placement and weight`() {
        val input = CampaignInput(
            name = "hero banner",
            channel = NotificationChannel.BANNER,
            segmentIds = emptyList(),
            placement = "hero",
            weight = 75
        )
        assertEquals("hero", input.placement)
        assertEquals(75, input.weight)
    }
}
