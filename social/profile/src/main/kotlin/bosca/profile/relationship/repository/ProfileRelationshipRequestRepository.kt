package bosca.profile.relationship.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.relationship.model.ProfileRelationshipRequest
import bosca.profile.relationship.model.ProfileRelationshipRequestStatus
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface ProfileRelationshipRequestRepository {

    @Query("select * from public.profile_relationship_requests where id = :id")
    suspend fun getById(id: UUID): ProfileRelationshipRequest?

    @Query(
        """
        select * from public.profile_relationship_requests
        where target_profile_id = :profileId and status = 'pending'
        order by type, requester_profile_id
        offset :offset limit :limit
        """
    )
    suspend fun getIncoming(profileId: UUID, offset: Long, limit: Int): List<ProfileRelationshipRequest>

    @Query(
        """
        select * from public.profile_relationship_requests
        where target_profile_id = :profileId and status = 'pending' and type = :type
        order by requester_profile_id
        offset :offset limit :limit
        """
    )
    suspend fun getIncomingByType(
        profileId: UUID,
        type: String,
        offset: Long,
        limit: Int,
    ): List<ProfileRelationshipRequest>

    @Query(
        """
        select * from public.profile_relationship_requests
        where requester_profile_id = :profileId and status = 'pending'
        order by target_profile_id, type
        offset :offset limit :limit
        """
    )
    suspend fun getOutgoing(profileId: UUID, offset: Long, limit: Int): List<ProfileRelationshipRequest>

    @Query(
        """
        select * from public.profile_relationship_requests
        where requester_profile_id = :profileId and status = 'pending' and type = :type
        order by target_profile_id
        offset :offset limit :limit
        """
    )
    suspend fun getOutgoingByType(
        profileId: UUID,
        type: String,
        offset: Long,
        limit: Int,
    ): List<ProfileRelationshipRequest>

    @Query(
        """
        insert into public.profile_relationship_requests (requester_profile_id, target_profile_id, type, attributes)
        values (:requesterProfileId, :targetProfileId, :type, :attributes)
        returning *
        """
    )
    suspend fun add(
        requesterProfileId: UUID,
        targetProfileId: UUID,
        type: String,
        attributes: JsonElement?,
    ): ProfileRelationshipRequest

    @Query(
        """
        update public.profile_relationship_requests
        set status = :status::public.profile_relationship_request_status,
            version = version + 1,
            modified = now()
        where id = :id and version = :version and status = 'pending'
        returning *
        """
    )
    suspend fun transition(
        id: UUID,
        version: Long,
        status: ProfileRelationshipRequestStatus,
    ): ProfileRelationshipRequest?
}
