package bosca.profile.relationship.service

import bosca.db.transaction
import bosca.profile.relationship.events.ProfileRelationshipRequested
import bosca.profile.relationship.events.ProfileRelationshipRequestApproved
import bosca.profile.relationship.events.dispatch
import bosca.profile.relationship.model.ProfileRelationshipRequest
import bosca.profile.relationship.model.ProfileRelationshipRequestStatus
import bosca.profile.relationship.repository.ProfileRelationshipRequestRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonElement

@ServiceImplementation
class ProfileRelationshipRequestServiceImpl(
    private val repository: ProfileRelationshipRequestRepository,
    private val relationshipService: ProfileRelationshipService,
) : ProfileRelationshipRequestService {

    override suspend fun getById(id: UUID): ProfileRelationshipRequest? = repository.getById(id)

    override suspend fun getIncoming(
        profileId: UUID,
        type: String?,
        offset: Long,
        limit: Int,
    ): List<ProfileRelationshipRequest> {
        validatePage(offset, limit)
        return if (type == null) {
            repository.getIncoming(profileId, offset, limit)
        } else {
            repository.getIncomingByType(profileId, type, offset, limit)
        }
    }

    override suspend fun getOutgoing(
        profileId: UUID,
        type: String?,
        offset: Long,
        limit: Int,
    ): List<ProfileRelationshipRequest> {
        validatePage(offset, limit)
        return if (type == null) {
            repository.getOutgoing(profileId, offset, limit)
        } else {
            repository.getOutgoingByType(profileId, type, offset, limit)
        }
    }

    override suspend fun request(
        requesterProfileId: UUID,
        targetProfileId: UUID,
        type: String,
        attributes: JsonElement?,
    ): ProfileRelationshipRequest = transaction {
        require(requesterProfileId != targetProfileId) { "a profile cannot request a relationship with itself" }
        check(relationshipService.getRelationship(requesterProfileId, targetProfileId, type) == null) {
            "relationship already exists"
        }
        val request = repository.add(requesterProfileId, targetProfileId, type, attributes)
        ProfileRelationshipRequested(request.id, requesterProfileId, targetProfileId, type).dispatch()
        request
    }

    override suspend fun approve(id: UUID): ProfileRelationshipRequest = transaction {
        val request = pendingRequest(id)
        val approved = transition(request, ProfileRelationshipRequestStatus.APPROVED)
        relationshipService.addRelationship(
            request.requesterProfileId,
            request.targetProfileId,
            request.type,
            request.attributes,
        )
        ProfileRelationshipRequestApproved(
            request.id,
            request.requesterProfileId,
            request.targetProfileId,
            request.type,
        ).dispatch()
        approved
    }

    override suspend fun decline(id: UUID): ProfileRelationshipRequest =
        transition(pendingRequest(id), ProfileRelationshipRequestStatus.DECLINED)

    override suspend fun cancel(id: UUID): ProfileRelationshipRequest =
        transition(pendingRequest(id), ProfileRelationshipRequestStatus.CANCELLED)

    private suspend fun pendingRequest(id: UUID): ProfileRelationshipRequest {
        val request = repository.getById(id) ?: throw NoSuchElementException("relationship request not found")
        check(request.status == ProfileRelationshipRequestStatus.PENDING) {
            "relationship request is not pending"
        }
        return request
    }

    private suspend fun transition(
        request: ProfileRelationshipRequest,
        status: ProfileRelationshipRequestStatus,
    ): ProfileRelationshipRequest = repository.transition(request.id, request.version, status)
        ?: error("relationship request was modified concurrently")

    private fun validatePage(offset: Long, limit: Int) {
        require(offset >= 0) { "offset must not be negative" }
        require(limit >= 0) { "limit must not be negative" }
    }
}
