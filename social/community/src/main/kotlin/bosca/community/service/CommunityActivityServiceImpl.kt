package bosca.community.service

import bosca.community.model.CommunityActivity
import bosca.community.repository.CommunityActivityRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonElement

@ServiceImplementation
class CommunityActivityServiceImpl(
    private val communityActivityRepository: CommunityActivityRepository
) : CommunityActivityService {

    override suspend fun getActivities(groupId: UUID): List<CommunityActivity> {
        return communityActivityRepository.getActivities(groupId)
    }

    override suspend fun getActivity(id: UUID): CommunityActivity? {
        return communityActivityRepository.getActivity(id)
    }

    override suspend fun createActivity(
        groupId: UUID,
        name: String,
        description: String,
        type: String,
        content: JsonElement?,
        schedule: JsonElement?
    ): CommunityActivity {
        return communityActivityRepository.createActivity(groupId, name, description, type, content, schedule)
    }

    override suspend fun updateActivity(
        id: UUID,
        name: String?,
        description: String?,
        type: String?,
        content: JsonElement?,
        schedule: JsonElement?
    ): CommunityActivity {
        return communityActivityRepository.updateActivity(id, name, description, type, content, schedule)
    }
}
