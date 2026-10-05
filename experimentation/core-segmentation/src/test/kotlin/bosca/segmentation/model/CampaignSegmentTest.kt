@file:OptIn(ExperimentalUuidApi::class)

package bosca.segmentation.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.ExperimentalUuidApi

class CampaignSegmentTest {

    @Test
    fun `CampaignSegment stores campaignId and segmentId`() {
        val campaignId = UUID.random()
        val segmentId = UUID.random()
        val cs = CampaignSegment(campaignId = campaignId, segmentId = segmentId)
        assertEquals(campaignId, cs.campaignId)
        assertEquals(segmentId, cs.segmentId)
    }

    @Test
    fun `CampaignSegment equality`() {
        val campaignId = UUID.random()
        val segmentId = UUID.random()
        val a = CampaignSegment(campaignId = campaignId, segmentId = segmentId)
        val b = CampaignSegment(campaignId = campaignId, segmentId = segmentId)
        assertEquals(a, b)
    }

    @Test
    fun `CampaignSegment inequality with different ids`() {
        val a = CampaignSegment(campaignId = UUID.random(), segmentId = UUID.random())
        val b = CampaignSegment(campaignId = UUID.random(), segmentId = UUID.random())
        assertNotEquals(a, b)
    }
}
