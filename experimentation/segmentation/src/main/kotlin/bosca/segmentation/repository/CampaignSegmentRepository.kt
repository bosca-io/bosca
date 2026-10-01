package bosca.segmentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.segmentation.model.CampaignSegment
import bosca.serialization.UUID

@Repository
interface CampaignSegmentRepository {

    @Query("select * from segmentation.campaign_segments where campaign_id = :campaignId")
    suspend fun getByCampaignId(campaignId: UUID): List<CampaignSegment>

    @Query("insert into segmentation.campaign_segments (campaign_id, segment_id) values (:campaignId, :segmentId) on conflict do nothing")
    suspend fun add(campaignId: UUID, segmentId: UUID)

    @Query("delete from segmentation.campaign_segments where campaign_id = :campaignId")
    suspend fun deleteByCampaignId(campaignId: UUID)
}
