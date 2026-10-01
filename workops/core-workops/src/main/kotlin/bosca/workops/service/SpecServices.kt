package bosca.workops.service

import bosca.security.model.PermissionService
import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.audit.RequirementHistoryEntry
import bosca.workops.model.audit.SpecHistoryEntry
import bosca.workops.model.requirement.CreateRequirementInput
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.model.requirement.RequirementComment
import bosca.workops.model.requirement.RequirementCommentInput
import bosca.workops.model.requirement.UpdateRequirementInput
import bosca.workops.model.spec.CreateSpecContextInput
import bosca.workops.model.spec.CreateSpecInput
import bosca.workops.model.spec.Spec
import bosca.workops.model.spec.SpecComment
import bosca.workops.model.spec.SpecCommentInput
import bosca.workops.model.spec.GenerationSource
import bosca.workops.model.spec.SpecContext
import bosca.workops.model.spec.SpecContextType
import bosca.workops.model.spec.SpecTaskGeneration
import bosca.workops.model.spec.UpdateSpecInput
import bosca.workops.model.task.Task
import bosca.comments.model.CommentStatus

interface SpecService : PermissionService<Spec, UUID> {

    suspend fun getById(id: UUID): Spec?

    /**
     * Returns a specification regardless of its soft-deletion state for audit and post-deletion
     * notification processing. Ordinary reads continue to use [getById].
     */
    suspend fun getByIdIncludingDeleted(id: UUID): Spec?

    suspend fun getByKey(key: String): Spec?

    suspend fun getByIds(ids: List<UUID>): List<Spec>

    suspend fun listByProject(projectId: UUID, offset: Long, limit: Int): List<Spec>

    suspend fun listByProgram(programId: UUID, offset: Long, limit: Int): List<Spec>

    suspend fun listByOwner(ownerProfileId: UUID, offset: Long, limit: Int): List<Spec>

    suspend fun create(
        input: CreateSpecInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        ownerProfileId: UUID,
    ): Spec

    suspend fun update(
        id: UUID,
        input: UpdateSpecInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Spec

    suspend fun softDelete(id: UUID, expectedVersion: Long, actingPrincipalId: UUID, actingProfileId: UUID?): Spec

    suspend fun restore(id: UUID, expectedVersion: Long, actingPrincipalId: UUID, actingProfileId: UUID?): Spec

    suspend fun listHistory(specId: UUID, offset: Long, limit: Int): List<SpecHistoryEntry>

    suspend fun listChildren(parentSpecId: UUID, offset: Long, limit: Int): List<Spec>

    suspend fun countChildren(parentSpecId: UUID): Long

    suspend fun transition(
        id: UUID,
        transitionId: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        resolutionId: UUID? = null,
    ): Spec

    suspend fun generateTasks(
        specId: UUID,
        metadataVersion: Int,
        source: GenerationSource,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        agentSessionId: UUID? = null,
    ): SpecTaskGeneration
}

interface RequirementService : PermissionService<Requirement, UUID> {

    suspend fun getById(id: UUID): Requirement?

    /** Returns a requirement even after soft deletion for durable event processing. */
    suspend fun getByIdIncludingDeleted(id: UUID): Requirement?

    suspend fun getByKey(key: String): Requirement?

    suspend fun getByIds(ids: List<UUID>): List<Requirement>

    suspend fun getByTaskId(taskId: UUID): Requirement?

    suspend fun listByParent(parentType: RequirementParent, parentId: UUID, offset: Long, limit: Int): List<Requirement>

    suspend fun countByParent(parentType: RequirementParent, parentId: UUID): Long

    suspend fun create(
        input: CreateRequirementInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Requirement

    suspend fun update(
        id: UUID,
        input: UpdateRequirementInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Requirement

    suspend fun softDelete(id: UUID, expectedVersion: Long, actingPrincipalId: UUID, actingProfileId: UUID?): Requirement

    suspend fun restore(id: UUID, expectedVersion: Long, actingPrincipalId: UUID, actingProfileId: UUID?): Requirement

    suspend fun syncStatusFromTask(
        taskId: UUID,
        statusId: UUID,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    )

    suspend fun moveToParent(
        id: UUID,
        newParentType: RequirementParent,
        newParentId: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Requirement

    suspend fun listHistory(requirementId: UUID, offset: Long, limit: Int): List<RequirementHistoryEntry>
}

interface RequirementCommentService : Service {

    suspend fun add(
        requirementId: UUID,
        input: RequirementCommentInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID,
        impersonatorId: UUID? = null,
    ): RequirementComment

    suspend fun getManager(requirementId: UUID, id: Long): RequirementComment?

    suspend fun getForProfile(requirementId: UUID, id: Long, profileId: UUID): RequirementComment?

    suspend fun getPublic(requirementId: UUID, id: Long): RequirementComment?

    suspend fun listManager(requirementId: UUID, offset: Long, limit: Long): List<RequirementComment>

    suspend fun listForProfile(requirementId: UUID, profileId: UUID, offset: Long, limit: Long): List<RequirementComment>

    suspend fun listPublic(requirementId: UUID, offset: Long, limit: Long): List<RequirementComment>

    suspend fun countManager(requirementId: UUID): Long

    suspend fun like(requirementId: UUID, commentId: Long, profileId: UUID)

    suspend fun unlike(requirementId: UUID, commentId: Long, profileId: UUID)

    suspend fun setStatus(requirementId: UUID, commentId: Long, status: CommentStatus, actingPrincipalId: UUID, actingProfileId: UUID?)

    suspend fun delete(requirementId: UUID, commentId: Long, actingPrincipalId: UUID, actingProfileId: UUID?)

    suspend fun deleteByAuthor(requirementId: UUID, commentId: Long, profileId: UUID, actingPrincipalId: UUID)
}

interface SpecCommentService : Service {

    suspend fun add(
        specId: UUID,
        input: SpecCommentInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID,
        impersonatorId: UUID? = null,
    ): SpecComment

    suspend fun getManager(specId: UUID, id: Long): SpecComment?

    suspend fun getForProfile(specId: UUID, id: Long, profileId: UUID): SpecComment?

    suspend fun getPublic(specId: UUID, id: Long): SpecComment?

    suspend fun listManager(specId: UUID, offset: Long, limit: Long): List<SpecComment>

    suspend fun listForProfile(specId: UUID, profileId: UUID, offset: Long, limit: Long): List<SpecComment>

    suspend fun listPublic(specId: UUID, offset: Long, limit: Long): List<SpecComment>

    suspend fun countManager(specId: UUID): Long

    suspend fun like(specId: UUID, commentId: Long, profileId: UUID)

    suspend fun unlike(specId: UUID, commentId: Long, profileId: UUID)

    suspend fun setStatus(specId: UUID, commentId: Long, status: CommentStatus, actingPrincipalId: UUID, actingProfileId: UUID?)

    suspend fun delete(specId: UUID, commentId: Long, actingPrincipalId: UUID, actingProfileId: UUID?)

    suspend fun deleteByAuthor(specId: UUID, commentId: Long, profileId: UUID, actingPrincipalId: UUID)
}

interface SpecContextService : Service {

    suspend fun listBySpec(specId: UUID): List<SpecContext>

    suspend fun listBySpecAndType(specId: UUID, contextType: SpecContextType): List<SpecContext>

    suspend fun add(
        specId: UUID,
        input: CreateSpecContextInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID,
    ): SpecContext

    suspend fun remove(
        specId: UUID,
        contextId: UUID,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    )
}
