package bosca.profile.relationship.repository

import bosca.profile.relationship.model.ProfileRelationship
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface ProfileRelationshipRepository {

    @Query("select * from profile_relationships where profile_id_1 = :profileId1 and profile_id_2 = :profileId2 and type = :type")
    suspend fun getRelationship(profileId1: UUID, profileId2: UUID, type: String): ProfileRelationship?

    @Query(
        """
        select *
        from profile_relationships
        where profile_id_1 = :profileId
        order by profile_id_2, type
        offset :offset
        limit :limit
        """
    )
    suspend fun getRelationships(
        profileId: UUID,
        offset: Long,
        limit: Int,
    ): List<ProfileRelationship>

    @Query(
        """
        select *
        from profile_relationships
        where profile_id_1 = :profileId
          and type = :type
        order by profile_id_2
        offset :offset
        limit :limit
        """
    )
    suspend fun getRelationshipsByType(
        profileId: UUID,
        type: String,
        offset: Long,
        limit: Int,
    ): List<ProfileRelationship>

    @Query(
        "insert into profile_relationships (profile_id_1, profile_id_2, type, attributes) " +
            "values (:profileId1, :profileId2, :type, :attributes) " +
            "on conflict (profile_id_1, profile_id_2, type) do nothing",
        returnUpdateCount = true,
    )
    suspend fun addRelationship(profileId1: UUID, profileId2: UUID, type: String, attributes: JsonElement?): Int

    @Query(
        "update profile_relationships set attributes = :attributes " +
            "where profile_id_1 = :profileId1 and profile_id_2 = :profileId2 and type = :type",
        returnUpdateCount = true,
    )
    suspend fun updateRelationship(profileId1: UUID, profileId2: UUID, type: String, attributes: JsonElement?): Int

    @Query("delete from profile_relationships where profile_id_1 = :profileId1 and profile_id_2 = :profileId2 and type = :type")
    suspend fun removeRelationship(profileId1: UUID, profileId2: UUID, type: String)
}
