package bosca.community.service

import bosca.community.events.PrayerCommentAddedEvent
import bosca.community.events.PrayerReactionAddedEvent
import bosca.community.events.PrayerReactionType
import bosca.community.events.dispatch
import bosca.community.model.PrayedByEntry
import bosca.community.model.Prayer
import bosca.community.model.PrayerAnniversary
import bosca.community.model.PrayerComment
import bosca.community.model.PrayerFeedFilter
import bosca.community.model.PrayerLike
import bosca.community.model.PrayerMilestone
import bosca.community.model.Prayers
import bosca.community.model.PrayerShare
import bosca.community.model.PrayerStatus
import bosca.community.repository.PrayerAnniversaryRepository
import bosca.community.repository.PrayerCommentRepository
import bosca.community.repository.PrayerCommunityGroupRepository
import bosca.community.repository.PrayerPermissionRepository
import bosca.community.repository.PrayerRepository
import bosca.community.repository.PrayerShareRepository
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonElement

@ServiceImplementation
class PrayerServiceImpl(
    private val prayerRepository: PrayerRepository,
    private val prayerCommentRepository: PrayerCommentRepository,
    private val prayerShareRepository: PrayerShareRepository,
    private val prayerAnniversaryRepository: PrayerAnniversaryRepository,
    private val prayerPermissionRepository: PrayerPermissionRepository,
    private val prayerCommunityGroupRepository: PrayerCommunityGroupRepository,
    private val communityService: CommunityService
) : PrayerService {

    override suspend fun getPermissions(entity: Prayer): List<EntityPermission> {
        return prayerPermissionRepository.getPermissionsByPrayerId(entity.id)
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val permissions = prayerPermissionRepository.getPermissionsByPrayerIds(batch.keys)
        batch.setData(batch.keys, permissions.groupBy { it.entityId })
    }

    override suspend fun getRequests(groupId: UUID, limit: Int, offset: Int): List<Prayer> {
        return prayerRepository.getRequests(groupId, limit, offset)
    }

    override suspend fun getRequests(groupId: UUID, status: List<PrayerStatus>?, limit: Int, offset: Int): Prayers {
        val statuses = status?.joinToString(",") { it.name.lowercase() }
        val prayers = if (statuses != null) {
            prayerRepository.getRequestsByStatus(groupId, statuses, limit, offset)
        } else {
            prayerRepository.getRequests(groupId, limit, offset)
        }
        val total = if (statuses != null) {
            prayerRepository.countRequestsByStatus(groupId, statuses)
        } else {
            prayerRepository.countRequests(groupId)
        }
        return Prayers(prayers = prayers, total = total)
    }

    override suspend fun getFeed(
        profileId: UUID,
        groupIds: List<UUID>?,
        status: List<PrayerStatus>?,
        limit: Int,
        offset: Int
    ): Prayers {
        val userGroups = communityService.getGroups(profileId).map { it.id }
        if (userGroups.isEmpty()) return Prayers(prayers = emptyList(), total = 0)
        val resolvedGroupIds = if (groupIds != null) {
            userGroups.filter { it in groupIds }
        } else {
            userGroups
        }
        if (resolvedGroupIds.isEmpty()) return Prayers(prayers = emptyList(), total = 0)
        val statuses = status?.joinToString(",") { it.name.lowercase() }
        val filter = PrayerFeedFilter(
            communityGroupIds = resolvedGroupIds,
            statuses = statuses,
            limit = limit,
            offset = offset
        )
        val prayers = if (statuses != null) {
            prayerRepository.getRequestsByGroupsAndStatus(filter)
        } else {
            prayerRepository.getRequestsByGroups(filter)
        }
        val total = if (statuses != null) {
            prayerRepository.countRequestsByGroupsAndStatus(filter)
        } else {
            prayerRepository.countRequestsByGroups(filter)
        }
        return Prayers(prayers = prayers, total = total)
    }

    override suspend fun getRequest(id: UUID): Prayer? {
        return prayerRepository.getRequest(id)
    }

    override suspend fun addRequest(
        groupId: UUID,
        profileId: UUID,
        title: String,
        content: JsonElement,
        attributes: JsonElement?
    ): Prayer = transaction {
        val prayer = prayerRepository.addRequest(profileId, title, content, attributes)
        prayerCommunityGroupRepository.addCommunityGroup(prayer.id, groupId)
        val usersGroup = communityService.getUsersGroup(groupId)
            ?: error("Users security group not found for community group: $groupId")
        val adminGroup = communityService.getAdminGroup(groupId)
            ?: error("Admin security group not found for community group: $groupId")
        prayerPermissionRepository.addPermission(prayer.id, usersGroup.id, PermissionAction.VIEW)
        prayerPermissionRepository.addPermission(prayer.id, adminGroup.id, PermissionAction.MANAGE)
        prayer
    }

    override suspend fun updateStatus(id: UUID, status: PrayerStatus): Prayer {
        return prayerRepository.updateStatus(id, status.name.lowercase())
            ?: error("Prayer not found: $id")
    }

    override suspend fun getCommunityGroupsForPrayer(prayerId: UUID): List<UUID> {
        return prayerCommunityGroupRepository.getCommunityGroupIds(prayerId)
    }

    override suspend fun markPrayed(prayerId: UUID, profileId: UUID): Int = transaction {
        val count = prayerRepository.incrementPrayerActionCount(prayerId) ?: error("Prayer not found: $prayerId")
        prayerRepository.addPrayedBy(prayerId, profileId)
        PrayerReactionAddedEvent(UUID.random(), prayerId, profileId, PrayerReactionType.PRAYED).dispatch()
        count
    }

    override suspend fun unmarkPrayed(prayerId: UUID, profileId: UUID): Int = transaction {
        val deleted = prayerRepository.deletePrayedBy(prayerId, profileId)
            ?: error("Not marked as prayed")
        prayerRepository.decrementPrayerActionCount(prayerId) ?: error("Prayer not found: $deleted")
    }

    override suspend fun getPrayedBy(prayerId: UUID, limit: Int, offset: Int): List<PrayedByEntry> {
        return prayerRepository.getPrayedBy(prayerId, limit, offset)
    }

    override suspend fun hasPrayed(prayerId: UUID, profileId: UUID): Boolean {
        return prayerRepository.hasPrayed(prayerId, profileId)
    }

    override suspend fun likePrayer(prayerId: UUID, profileId: UUID): Int = transaction {
        val count = prayerRepository.incrementLikeCount(prayerId) ?: error("Prayer not found: $prayerId")
        prayerRepository.addLike(prayerId, profileId)
        PrayerReactionAddedEvent(UUID.random(), prayerId, profileId, PrayerReactionType.LIKED).dispatch()
        count
    }

    override suspend fun unlikePrayer(prayerId: UUID, profileId: UUID): Int = transaction {
        val deleted = prayerRepository.deleteLike(prayerId, profileId)
            ?: error("Not liked")
        prayerRepository.decrementLikeCount(prayerId) ?: error("Prayer not found: $deleted")
    }

    override suspend fun getLikes(prayerId: UUID, limit: Int, offset: Int): List<PrayerLike> {
        return prayerRepository.getLikes(prayerId, limit, offset)
    }

    override suspend fun hasLiked(prayerId: UUID, profileId: UUID): Boolean {
        return prayerRepository.hasLiked(prayerId, profileId)
    }

    override suspend fun addComment(prayerId: UUID, profileId: UUID, content: String, parentId: Long?, attributes: JsonElement?): PrayerComment = transaction {
        val comment = prayerCommentRepository.addComment(prayerId, parentId, profileId, content, attributes)
        prayerRepository.incrementCommentCount(prayerId)
        PrayerCommentAddedEvent(comment.id, prayerId, profileId, parentId).dispatch()
        comment
    }

    override suspend fun getComment(id: Long): PrayerComment? {
        return prayerCommentRepository.getComment(id)
    }

    override suspend fun getComments(prayerId: UUID, limit: Int, offset: Int): List<PrayerComment> {
        return prayerCommentRepository.getComments(prayerId, limit, offset)
    }

    override suspend fun getReplies(parentId: Long, limit: Int, offset: Int): List<PrayerComment> {
        return prayerCommentRepository.getReplies(parentId, limit, offset)
    }

    override suspend fun deleteComment(prayerId: UUID, commentId: Long): Unit = transaction {
        prayerCommentRepository.deleteComment(commentId)
        prayerRepository.decrementCommentCount(prayerId)
    }

    override suspend fun sharePrayer(prayerId: UUID, profileId: UUID) {
        prayerShareRepository.addShare(prayerId, profileId)
    }

    override suspend fun unsharePrayer(prayerId: UUID, profileId: UUID) {
        prayerShareRepository.deleteShare(prayerId, profileId)
    }

    override suspend fun getShares(prayerId: UUID): List<PrayerShare> {
        return prayerShareRepository.getShares(prayerId)
    }

    override suspend fun getSharedWithMe(profileId: UUID, limit: Int, offset: Int): Prayers {
        val prayers = prayerShareRepository.getSharedWithProfile(profileId, limit, offset)
        val total = prayerShareRepository.countSharedWithProfile(profileId)
        return Prayers(prayers = prayers, total = total)
    }

    override suspend fun getAnniversaries(prayerId: UUID): List<PrayerAnniversary> {
        return prayerAnniversaryRepository.getAnniversaries(prayerId)
    }

    override suspend fun suppressAnniversaries(id: UUID, suppress: Boolean): Prayer {
        return prayerRepository.updateSuppressAnniversaries(id, suppress)
            ?: error("Prayer not found: $id")
    }

    override suspend fun scanAndPostAnniversaries() {
        val prayers = prayerRepository.getAnsweredPrayersForAnniversaryScan()
        for (prayer in prayers) {
            val answeredAt = prayer.answeredAt ?: continue
            val dueMilestones = PrayerMilestone.dueMilestones(answeredAt)
            val posted = prayerAnniversaryRepository.getAnniversaries(prayer.id).map { it.milestone }.toSet()
            val newMilestones = dueMilestones.filter { it !in posted }
            for (milestone in newMilestones) {
                prayerAnniversaryRepository.addAnniversary(prayer.id, milestone)
            }
            if (newMilestones.isNotEmpty()) {
                prayerRepository.updateStatus(prayer.id, "answered")
            }
        }
    }

    override suspend fun deleteRequest(id: UUID) {
        prayerRepository.deleteRequest(id)
    }
}
