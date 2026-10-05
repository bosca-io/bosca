package bosca.workops.service

import bosca.db.transaction
import bosca.comments.model.CommentStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.spec.SpecComment
import bosca.workops.model.spec.SpecCommentInput
import bosca.workops.model.spec.SpecCommented
import bosca.workops.model.spec.dispatch
import bosca.workops.repository.SpecCommentRepository
import bosca.workops.repository.SpecHistoryRepository
import bosca.workops.repository.SpecRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

@ServiceImplementation
class SpecCommentServiceImpl(
    private val commentRepository: SpecCommentRepository,
    private val specRepository: SpecRepository,
    private val specHistoryRepository: SpecHistoryRepository,
    private val json: Json,
) : SpecCommentService {

    override suspend fun add(
        specId: UUID,
        input: SpecCommentInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID,
        impersonatorId: UUID?,
    ): SpecComment = transaction {
        if (input.content.isBlank()) {
            throw WorkOpsValidationException("content", "must be non-blank")
        }
        specRepository.getActiveById(specId)
            ?: throw WorkOpsNotFoundException("Spec", specId.toString())

        val parentId = input.parentId
        if (parentId != null) {
            val parent = commentRepository.getById(parentId)
                ?: throw WorkOpsNotFoundException("SpecComment", parentId.toString())
            if (parent.specId != specId) {
                throw WorkOpsValidationException("parentId", "parent comment is on a different spec")
            }
        }

        val newId = commentRepository.add(
            parentId = input.parentId,
            specId = specId,
            profileId = actingProfileId,
            impersonatorId = impersonatorId,
            visibility = input.visibility,
            content = input.content,
            attributes = input.attributes,
            systemAttributes = input.systemAttributes,
        )
        val saved = commentRepository.getById(newId)
            ?: error("freshly inserted spec comment $newId vanished")

        writeHistory(
            specId = specId,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            fieldKey = "comment",
            toValue = JsonPrimitive("added:$newId"),
        )
        SpecCommented(specId = specId, commentId = newId, profileId = actingProfileId).dispatch()
        saved
    }

    override suspend fun getManager(specId: UUID, id: Long): SpecComment? =
        commentRepository.getManager(specId, id)

    override suspend fun getForProfile(specId: UUID, id: Long, profileId: UUID): SpecComment? =
        commentRepository.getForProfile(specId, id, profileId)

    override suspend fun getPublic(specId: UUID, id: Long): SpecComment? =
        commentRepository.getPublic(specId, id)

    override suspend fun listManager(specId: UUID, offset: Long, limit: Long): List<SpecComment> =
        commentRepository.listManager(specId, offset, limit)

    override suspend fun listForProfile(specId: UUID, profileId: UUID, offset: Long, limit: Long): List<SpecComment> =
        commentRepository.listForProfile(specId, profileId, offset, limit)

    override suspend fun listPublic(specId: UUID, offset: Long, limit: Long): List<SpecComment> =
        commentRepository.listPublic(specId, offset, limit)

    override suspend fun countManager(specId: UUID): Long =
        commentRepository.countManager(specId)

    override suspend fun like(specId: UUID, commentId: Long, profileId: UUID) {
        commentRepository.addLikeRow(commentId, profileId)
        commentRepository.incrementLikes(specId, commentId)
        Unit
    }

    override suspend fun unlike(specId: UUID, commentId: Long, profileId: UUID) {
        val deleted = commentRepository.deleteLikeRow(commentId, profileId)
        if (deleted != null) {
            commentRepository.decrementLikes(specId, commentId)
        }
    }

    override suspend fun setStatus(
        specId: UUID,
        commentId: Long,
        status: CommentStatus,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ) {
        commentRepository.setStatus(specId, commentId, status)
        writeHistory(
            specId = specId,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            fieldKey = "comment_status",
            toValue = JsonPrimitive("$commentId:$status"),
        )
    }

    override suspend fun delete(
        specId: UUID,
        commentId: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ) {
        commentRepository.softDelete(specId, commentId)
        writeHistory(
            specId = specId,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            fieldKey = "comment",
            toValue = JsonPrimitive("deleted:$commentId"),
        )
    }

    override suspend fun deleteByAuthor(
        specId: UUID,
        commentId: Long,
        profileId: UUID,
        actingPrincipalId: UUID,
    ) {
        commentRepository.softDeleteByProfile(specId, commentId, profileId)
        writeHistory(
            specId = specId,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = profileId,
            fieldKey = "comment",
            toValue = JsonPrimitive("deleted_by_author:$commentId"),
        )
    }

    private suspend fun writeHistory(
        specId: UUID,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        fieldKey: String,
        toValue: kotlinx.serialization.json.JsonElement,
    ) {
        specHistoryRepository.add(
            specId = specId,
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
