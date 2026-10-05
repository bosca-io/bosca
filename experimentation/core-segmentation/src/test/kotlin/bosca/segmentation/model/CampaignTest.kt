package bosca.segmentation.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampaignTest {

    @Test
    fun `Campaign defaults placement to null`() {
        val campaign = Campaign(name = "test", channel = NotificationChannel.BANNER)
        assertNull(campaign.placement)
    }

    @Test
    fun `Campaign defaults weight to 0`() {
        val campaign = Campaign(name = "test", channel = NotificationChannel.BANNER)
        assertEquals(0, campaign.weight)
    }

    @Test
    fun `Campaign stores placement and weight`() {
        val campaign = Campaign(
            name = "hero banner",
            channel = NotificationChannel.BANNER,
            placement = "hero",
            weight = 80
        )
        assertEquals("hero", campaign.placement)
        assertEquals(80, campaign.weight)
    }

    @Test
    fun `Campaign defaults status to DRAFT`() {
        val campaign = Campaign(name = "test", channel = NotificationChannel.BANNER)
        assertEquals(NotificationStatus.DRAFT, campaign.status)
    }

    @Test
    fun `Campaign copy preserves placement and weight`() {
        val original = Campaign(
            name = "original",
            channel = NotificationChannel.BANNER,
            placement = "top",
            weight = 50
        )
        val copied = original.copy(name = "updated")
        assertEquals("updated", copied.name)
        assertEquals("top", copied.placement)
        assertEquals(50, copied.weight)
    }
}
