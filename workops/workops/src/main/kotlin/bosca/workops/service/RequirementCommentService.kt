package bosca.workops.service

import bosca.db.transaction
import bosca.comments.model.CommentStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.requirement.RequirementComment
import bosca.workops.model.requirement.RequirementCommentInput
import bosca.workops.model.requirement.RequirementCommented
import bosca.workops.model.requirement.dispatch
import bosca.workops.repository.RequirementCommentRepository
import bosca.workops.repository.RequirementHistoryRepository
import bosca.workops.repository.RequirementRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

@ServiceImplementation
class RequirementCommentServiceImpl(
    private val commentRepository: RequirementCommentRepository,
    private val requirementRepository: RequirementRepository,
    private val requirementHistoryRepository: RequirementHistoryRepository,
    private val json: Json,
) : RequirementCommentService {

    override suspend fun add(
        requirementId: UUID,
        input: RequirementCommentInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID,
        impersonatorId: UUID?,
    ): RequirementComment = transaction {
        if (input.content.isBlank()) {
            throw WorkOpsValidationException("content", "must be non-blank")
        }
        requirementRepository.getActiveById(requirementId)
            ?: throw WorkOpsNotFoundException("Requirement", requirementId.toString())

        val parentId = input.parentId
        if (parentId != null) {
            val parent = commentRepository.getById(parentId)
                ?: throw WorkOpsNotFoundException("RequirementComment", parentId.toString())
            if (parent.requirementId != requirementId) {
                throw WorkOpsValidationException("parentId", "parent comment is on a different requirement")
            }
        }

        val newId = commentRepository.add(
            parentId = input.parentId,
            requirementId = requirementId,
            profileId = actingProfileId,
            impersonatorId = impersonatorId,
            visibility = input.visibility,
            content = input.content,
            attributes = input.attributes,
            systemAttributes = input.systemAttributes,
        )
        val saved = commentRepository.getById(newId)
            ?: error("freshly inserted requirement comment $newId vanished")

        writeHistory(
            requirementId = requirementId,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            fieldKey = "comment",
            toValue = JsonPrimitive("added:$newId"),
        )
        RequirementCommented(requirementId = requirementId, commentId = newId, profileId = actingProfileId).dispatch()
        saved
    }

    override suspend fun getManager(requirementId: UUID, id: Long): RequirementComment? =
        commentRepository.getManager(requirementId, id)

    override suspend fun getForProfile(requirementId: UUID, id: Long, profileId: UUID): RequirementComment? =
        commentRepository.getForProfile(requirementId, id, profileId)

    override suspend fun getPublic(requirementId: UUID, id: Long): RequirementComment? =
        commentRepository.getPublic(requirementId, id)

    override suspend fun listManager(requirementId: UUID, offset: Long, limit: Long): List<RequirementComment> =
        commentRepository.listManager(requirementId, offset, limit)

    override suspend fun listForProfile(requirementId: UUID, profileId: UUID, offset: Long, limit: Long): List<RequirementComment> =
        commentRepository.listForProfile(requirementId, profileId, offset, limit)

    override suspend fun listPublic(requirementId: UUID, offset: Long, limit: Long): List<RequirementComment> =
        commentRepository.listPublic(requirementId, offset, limit)

    override suspend fun countManager(requirementId: UUID): Long =
        commentRepository.countManager(requirementId)

    override suspend fun like(requirementId: UUID, commentId: Long, profileId: UUID) {
        commentRepository.addLikeRow(commentId, profileId)
        commentRepository.incrementLikes(requirementId, commentId)
        Unit
    }

    override suspend fun unlike(requirementId: UUID, commentId: Long, profileId: UUID) {
        val deleted = commentRepository.deleteLikeRow(commentId, profileId)
        if (deleted != null) {
            commentRepository.decrementLikes(requirementId, commentId)
        }
    }

    override suspend fun setStatus(
        requirementId: UUID,
        commentId: Long,
        status: CommentStatus,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ) {
        commentRepository.setStatus(requirementId, commentId, status)
        writeHistory(
            requirementId = requirementId,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            fieldKey = "comment_status",
            toValue = JsonPrimitive("$commentId:$status"),
        )
    }

    override suspend fun delete(
        requirementId: UUID,
        commentId: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ) {
        commentRepository.softDelete(requirementId, commentId)
        writeHistory(
            requirementId = requirementId,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            fieldKey = "comment",
            toValue = JsonPrimitive("deleted:$commentId"),
        )
    }

    override suspend fun deleteByAuthor(
        requirementId: UUID,
        commentId: Long,
        profileId: UUID,
        actingPrincipalId: UUID,
    ) {
        commentRepository.softDeleteByProfile(requirementId, commentId, profileId)
        writeHistory(
            requirementId = requirementId,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = profileId,
            fieldKey = "comment",
            toValue = JsonPrimitive("deleted_by_author:$commentId"),
        )
    }

    private suspend fun writeHistory(
        requirementId: UUID,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        fieldKey: String,
        toValue: kotlinx.serialization.json.JsonElement,
    ) {
        requirementHistoryRepository.add(
            requirementId = requirementId,
            changedAt = OffsetDateTime.now(),
            changedByPrincipalId = actingPrincipalId,
            changedByProfileId = actingProfileId,
            changes = json.encodeToJsonElement(
                ListSerializer(FieldChange.serializer()),
                listOf(FieldChange(fieldKey = fieldKey, toValue = toValue)),
            ),
        )
    }
}
