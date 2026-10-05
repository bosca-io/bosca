package bosca.profile.relationship.service

import bosca.profile.relationship.model.ProfileRelationshipRequest
import bosca.service.Service
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/** Service for creating, reviewing, and listing requests to establish profile relationships. */
interface ProfileRelationshipRequestService : Service {

    /** Returns a relationship request by identifier, or `null` when it does not exist. */
    suspend fun getById(id: UUID): ProfileRelationshipRequest?

    /** Returns pending requests sent to [profileId], optionally filtered by relationship [type]. */
    suspend fun getIncoming(
        profileId: UUID,
        type: String?,
        offset: Long,
        limit: Int,
    ): List<ProfileRelationshipRequest>

    /** Returns pending requests sent from [profileId], optionally filtered by relationship [type]. */
    suspend fun getOutgoing(
        profileId: UUID,
        type: String?,
        offset: Long,
        limit: Int,
    ): List<ProfileRelationshipRequest>

    /** Creates a pending request from [requesterProfileId] to [targetProfileId]. */
    suspend fun request(
        requesterProfileId: UUID,
        targetProfileId: UUID,
        type: String,
        attributes: JsonElement? = null,
    ): ProfileRelationshipRequest

    /** Approves a pending request and creates its actual relationship. */
    suspend fun approve(id: UUID): ProfileRelationshipRequest

    /** Declines a pending request without creating a relationship. */
    suspend fun decline(id: UUID): ProfileRelationshipRequest

    /** Cancels a pending request without creating a relationship. */
    suspend fun cancel(id: UUID): ProfileRelationshipRequest
}
