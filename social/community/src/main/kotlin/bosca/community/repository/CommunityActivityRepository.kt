package bosca.community.repository

import bosca.community.model.CommunityActivity
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface CommunityActivityRepository {

    @Query("select * from community_activities where group_id = :groupId")
    suspend fun getActivities(groupId: UUID): List<CommunityActivity>

    @Query("select * from community_activities where id = :id")
    suspend fun getActivity(id: UUID): CommunityActivity?

    @Query("insert into community_activities (group_id, name, description, type, content, schedule) values (:groupId, :name, :description, :type, :content, :schedule) returning *")
    suspend fun createActivity(groupId: UUID, name: String, description: String, type: String, content: JsonElement?, schedule: JsonElement?): CommunityActivity

    @Query("update community_activities set name = coalesce(:name, name), description = coalesce(:description, description), type = coalesce(:type, type), content = coalesce(:content, content), schedule = coalesce(:schedule, schedule) where id = :id returning *")
    suspend fun updateActivity(id: UUID, name: String?, description: String?, type: String?, content: JsonElement?, schedule: JsonElement?): CommunityActivity
}
