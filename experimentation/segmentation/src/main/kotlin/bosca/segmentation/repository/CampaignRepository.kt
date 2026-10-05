package bosca.segmentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.segmentation.model.BannerWeight
import bosca.segmentation.model.Campaign
import bosca.segmentation.model.NotificationStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@Repository
interface CampaignRepository {

    @Query("select * from segmentation.campaigns order by created desc limit :limit offset :offset")
    suspend fun getAll(offset: Long, limit: Int): List<Campaign>

    @Query("select * from segmentation.campaigns where id = :id")
    suspend fun getById(id: UUID): Campaign?

    @Query("""
        insert into segmentation.campaigns (name, channel, status, content, scheduled_at, ended_at, placement, weight)
        values (:name, (:channel)::segmentation.notification_channel, (:status)::segmentation.notification_status, :content::jsonb, :scheduledAt, :endedAt, :placement, :weight)
        returning *
    """)
    suspend fun add(campaign: Campaign): Campaign

    @Query("""
        update segmentation.campaigns
        set name = :name, channel = (:channel)::segmentation.notification_channel,
            content = :content::jsonb, scheduled_at = :scheduledAt, ended_at = :endedAt,
            placement = :placement, weight = :weight, modified = now()
        where id = :id
        returning *
    """)
    suspend fun update(campaign: Campaign): Campaign

    @Query("update segmentation.campaigns set status = (:status)::segmentation.notification_status, modified = now() where id = :id returning *")
    suspend fun updateStatus(id: UUID, status: NotificationStatus): Campaign

    @Query("""
        update segmentation.campaigns
        set status = (:newStatus)::segmentation.notification_status, modified = now()
        where id = :id and status in ('draft', 'scheduled', 'cancelled')
        returning *
    """)
    suspend fun updateStatusIfSendable(id: UUID, newStatus: NotificationStatus): Campaign?

    @Query("update segmentation.campaigns set scheduled_job_id = :scheduledJobId, modified = now() where id = :id returning *")
    suspend fun updateScheduledJobId(id: UUID, scheduledJobId: UUID?): Campaign

    @Query("update segmentation.campaigns set status = (:status)::segmentation.notification_status, sent_at = :sentAt, sent_count = :sentCount, modified = now() where id = :id returning *")
    suspend fun updateSentStatus(id: UUID, status: NotificationStatus, sentAt: OffsetDateTime, sentCount: Long): Campaign

    @Query("delete from segmentation.campaigns where id = :id")
    suspend fun deleteById(id: UUID)

    @Query("""
        select distinct c.* from segmentation.campaigns c
        left join segmentation.campaign_segments cs on cs.campaign_id = c.id
        left join segmentation.segment_members sm on sm.segment_id = cs.segment_id
        where c.channel = 'banner' and c.status = 'active'
          and (c.ended_at is null or c.ended_at > now())
          and (sm.profile_id = :profileId
               or exists (select 1 from segmentation.campaign_segments cs2
                          join segmentation.segments s on s.id = cs2.segment_id
                          where cs2.campaign_id = c.id and s.type = 'everyone'))
        order by c.weight desc
    """)
    suspend fun getActiveBannersForProfile(profileId: UUID): List<Campaign>

    @Query("""
        select c.* from segmentation.campaigns c
        where c.channel = 'banner' and c.status = 'active'
          and (c.ended_at is null or c.ended_at > now())
          and exists (select 1 from segmentation.campaign_segments cs
                      join segmentation.segments s on s.id = cs.segment_id
                      where cs.campaign_id = c.id and s.type = 'everyone')
        order by c.weight desc
    """)
    suspend fun getActiveBannersEveryone(): List<Campaign>

    @Query("""
        select distinct c.id, c.weight from segmentation.campaigns c
        left join segmentation.campaign_segments cs on cs.campaign_id = c.id
        left join segmentation.segment_members sm on sm.segment_id = cs.segment_id
        where c.channel = 'banner' and c.status = 'active'
          and c.placement = :placement
          and (c.ended_at is null or c.ended_at > now())
          and (sm.profile_id = :profileId
               or exists (select 1 from segmentation.campaign_segments cs2
                          join segmentation.segments s on s.id = cs2.segment_id
                          where cs2.campaign_id = c.id and s.type = 'everyone'))
    """)
    suspend fun getActiveBannerWeightsForPlacement(profileId: UUID, placement: String): List<BannerWeight>

    @Query("""
        select c.id, c.weight from segmentation.campaigns c
        where c.channel = 'banner' and c.status = 'active'
          and c.placement = :placement
          and (c.ended_at is null or c.ended_at > now())
          and exists (select 1 from segmentation.campaign_segments cs
                      join segmentation.segments s on s.id = cs.segment_id
                      where cs.campaign_id = c.id and s.type = 'everyone')
    """)
    suspend fun getActiveBannerWeightsForPlacementEveryone(placement: String): List<BannerWeight>
}
