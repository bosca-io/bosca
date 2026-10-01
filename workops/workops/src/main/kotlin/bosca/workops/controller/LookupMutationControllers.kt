package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.links.CreateTaskLinkTypeInput
import bosca.workops.model.links.TaskLinkType
import bosca.workops.model.links.UpdateTaskLinkTypeInput
import bosca.workops.model.task.CreatePriorityInput
import bosca.workops.model.task.CreateResolutionInput
import bosca.workops.model.task.CreateTaskTypeInput
import bosca.workops.model.task.Priority
import bosca.workops.model.task.Resolution
import bosca.workops.model.task.TaskType
import bosca.workops.model.task.UpdatePriorityInput
import bosca.workops.model.task.UpdateResolutionInput
import bosca.workops.model.task.UpdateTaskTypeInput
import bosca.workops.model.workflow.CreateStatusInput
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.UpdateStatusInput
import bosca.workops.service.PriorityService
import bosca.workops.service.ResolutionService
import bosca.workops.service.StatusService
import bosca.workops.service.TaskLinkService
import bosca.workops.service.TaskTypeService

// ---------------------------------------------------------------------------
// Marker objects for KSP-generated GraphQL wiring.  Each object anchors a
// @TypeController whose generated provider registers the corresponding
// GraphQL type's data-fetchers.
// ---------------------------------------------------------------------------

/**
 * Verifies that the caller belongs to the `sa` or `administrators` group.
 * All lookup-data mutations are admin-only operations.
 */
private fun requireAdmin(authentication: AuthenticationContext) {
    val principal = authentication.principal() ?: error("Authentication required")
    if (!principal.hasGroup("sa") && !principal.hasGroup("administrators")) {
        error("Administrative access required")
    }
}

/** Marker for the `WorkOpsTaskTypesMutation` GraphQL type. */
object WorkOpsTaskTypesMutation

/** Admin CRUD controller for [TaskType] reference data (R3). */
@TypeController
class TaskTypeMutationController(
    private val service: TaskTypeService,
) : GraphQLController<WorkOpsTaskTypesMutation> {

    /** Creates a new task type from the supplied input fields. */
    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateTaskTypeInput): TaskType {
        requireAdmin(authentication)
        return service.create(input)
    }

    /** Updates an existing task type with optimistic-concurrency control. */
    @Field
    suspend fun update(authentication: AuthenticationContext, id: UUID, input: UpdateTaskTypeInput): TaskType {
        requireAdmin(authentication)
        return service.update(id, input)
    }

    /** Deletes a task type by primary key. Returns `true` on success. */
    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        requireAdmin(authentication)
        service.delete(id)
        return true
    }
}

/** Marker for the `WorkOpsPrioritiesMutation` GraphQL type. */
object WorkOpsPrioritiesMutation

/** Admin CRUD controller for [Priority] reference data (R2). */
@TypeController
class PriorityMutationController(
    private val service: PriorityService,
) : GraphQLController<WorkOpsPrioritiesMutation> {

    /** Creates a new priority from the supplied input fields. */
    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreatePriorityInput): Priority {
        requireAdmin(authentication)
        return service.create(input)
    }

    /** Updates an existing priority with optimistic-concurrency control. */
    @Field
    suspend fun update(authentication: AuthenticationContext, id: UUID, input: UpdatePriorityInput): Priority {
        requireAdmin(authentication)
        return service.update(id, input)
    }

    /** Deletes a priority by primary key. Returns `true` on success. */
    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        requireAdmin(authentication)
        service.delete(id)
        return true
    }
}

/** Marker for the `WorkOpsResolutionsMutation` GraphQL type. */
object WorkOpsResolutionsMutation

/** Admin CRUD controller for [Resolution] reference data (R9). */
@TypeController
class ResolutionMutationController(
    private val service: ResolutionService,
) : GraphQLController<WorkOpsResolutionsMutation> {

    /** Creates a new resolution from the supplied input fields. */
    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateResolutionInput): Resolution {
        requireAdmin(authentication)
        return service.create(input)
    }

    /** Updates an existing resolution with optimistic-concurrency control. */
    @Field
    suspend fun update(authentication: AuthenticationContext, id: UUID, input: UpdateResolutionInput): Resolution {
        requireAdmin(authentication)
        return service.update(id, input)
    }

    /** Deletes a resolution by primary key. Returns `true` on success. */
    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        requireAdmin(authentication)
        service.delete(id)
        return true
    }
}

/** Marker for the `WorkOpsStatusesMutation` GraphQL type. */
object WorkOpsStatusesMutation

/** Admin CRUD controller for [Status] reference data (R4). */
@TypeController
class StatusMutationController(
    private val service: StatusService,
) : GraphQLController<WorkOpsStatusesMutation> {

    /** Creates a new status from the supplied input fields. */
    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateStatusInput): Status {
        requireAdmin(authentication)
        return service.create(input)
    }

    /** Updates an existing status with optimistic-concurrency control. */
    @Field
    suspend fun update(authentication: AuthenticationContext, id: UUID, input: UpdateStatusInput): Status {
        requireAdmin(authentication)
        return service.update(id, input)
    }

    /** Deletes a status by primary key. Returns `true` on success. */
    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        requireAdmin(authentication)
        service.delete(id)
        return true
    }
}

/** Marker for the `WorkOpsTaskLinkTypesMutation` GraphQL type. */
object WorkOpsTaskLinkTypesMutation

/** Admin CRUD controller for [TaskLinkType] reference data (R7). */
@TypeController
class TaskLinkTypeMutationController(
    private val service: TaskLinkService,
) : GraphQLController<WorkOpsTaskLinkTypesMutation> {

    /** Creates a new task link type from the supplied input fields. */
    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateTaskLinkTypeInput): TaskLinkType {
        requireAdmin(authentication)
        return service.createLinkType(input)
    }

    /** Updates an existing task link type with optimistic-concurrency control. */
    @Field
    suspend fun update(authentication: AuthenticationContext, id: UUID, input: UpdateTaskLinkTypeInput): TaskLinkType {
        requireAdmin(authentication)
        return service.updateLinkType(id, input)
    }

    /** Deletes a task link type by primary key. Returns `true` on success. */
    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        requireAdmin(authentication)
        service.deleteLinkType(id)
        return true
    }
}
