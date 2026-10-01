package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.workops.model.bql.BqlError
import bosca.workops.model.bql.SavedFilter
import bosca.workops.model.bql.SavedFilterInput
import bosca.workops.service.SavedFilterService
import bosca.workops.service.SpecPermissionEvaluator
import bosca.workops.service.SpecQueryService
import bosca.workops.service.SpecSearchResult
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskQueryService
import bosca.workops.service.TaskSearchResult

/**
 * Validation / search results live in the workops GraphQL surface;
 * the wire shape is `BqlError`, `BqlValidationResult`,
 * `TaskSearchResult`.
 */
@TypeController(type = "WorkOpsBqlError")
class BqlErrorTypeController : GraphQLController<BqlError> {
    @Field
    fun message(e: BqlError) = e.message
    @Field
    fun start(e: BqlError) = e.start
    @Field
    fun end(e: BqlError) = e.end
    @Field
    fun hint(e: BqlError) = e.hint
}

/** Wire wrapper for the validate-only endpoint. */
data class BqlValidationResult(val errors: List<BqlError>)

@TypeController(type = "WorkOpsBqlValidationResult")
class BqlValidationResultTypeController : GraphQLController<BqlValidationResult> {
    @Field
    fun errors(r: BqlValidationResult) = r.errors
}

@TypeController(type = "WorkOpsTaskSearchResult")
class TaskSearchResultTypeController : GraphQLController<TaskSearchResult> {
    @Field
    fun rows(r: TaskSearchResult) = r.rows
    @Field
    fun freeTextTerms(r: TaskSearchResult) = r.freeTextTerms
}

@TypeController(type = "WorkOpsSpecSearchResult")
class SpecSearchResultTypeController : GraphQLController<SpecSearchResult> {
    @Field
    fun rows(r: SpecSearchResult) = r.rows
    @Field
    fun freeTextTerms(r: SpecSearchResult) = r.freeTextTerms
}

@TypeController(type = "WorkOpsSavedFilter")
class SavedFilterTypeController : GraphQLController<SavedFilter> {
    @Field
    fun id(f: SavedFilter) = f.id
    @Field
    fun ownerProfileId(f: SavedFilter) = f.ownerProfileId
    @Field
    fun name(f: SavedFilter) = f.name
    @Field
    fun description(f: SavedFilter) = f.description
    @Field
    fun bqlSource(f: SavedFilter) = f.bqlSource
    @Field
    fun parsedAst(f: SavedFilter): kotlinx.serialization.json.JsonElement = f.parsedAst
    @Field
    fun createdAt(f: SavedFilter) = f.createdAt
    @Field
    fun modifiedAt(f: SavedFilter) = f.modifiedAt
    @Field
    fun version(f: SavedFilter) = f.version
}

object WorkOpsSavedFilters

@TypeController
class SavedFilterQueryController(
    private val service: SavedFilterService,
    private val taskQueryService: TaskQueryService,
    private val specQueryService: SpecQueryService,
    private val taskPermissionEvaluator: TaskPermissionEvaluator,
    private val specPermissionEvaluator: SpecPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
    private val profileService: ProfileService,
) : GraphQLController<WorkOpsSavedFilters> {

    @Field
    suspend fun mine(authentication: AuthenticationContext, offset: Long, limit: Int): List<SavedFilter> {
        val profileId = resolveProfileId(authentication) ?: return emptyList()
        return service.listForOwner(profileId, offset, limit)
    }

    @Field
    suspend fun savedFilter(authentication: AuthenticationContext, id: UUID): SavedFilter? {
        val filter = service.getById(id) ?: return null
        val profileId = resolveProfileId(authentication)
        if (filter.ownerProfileId != profileId && !groupEvaluator.hasAdminGroup(authentication)) {
            return null
        }
        return filter
    }

    @Field
    suspend fun validateBql(authentication: AuthenticationContext, source: String): BqlValidationResult {
        return BqlValidationResult(taskQueryService.validate(source))
    }

    @Field
    suspend fun searchTasks(
        authentication: AuthenticationContext,
        source: String,
        offset: Long,
        limit: Int,
    ): TaskSearchResult {
        val profileId = resolveProfileId(authentication)
        val result = taskQueryService.search(source, profileId, offset, limit)
        val filtered = taskPermissionEvaluator.filterAllowed(authentication, result.rows, PermissionAction.VIEW)
        return result.copy(rows = filtered)
    }

    @Field
    suspend fun searchSpecs(
        authentication: AuthenticationContext,
        source: String,
        offset: Long,
        limit: Int,
    ): SpecSearchResult {
        val profileId = resolveProfileId(authentication)
        val result = specQueryService.search(source, profileId, offset, limit)
        val filtered = specPermissionEvaluator.filterAllowed(authentication, result.rows, PermissionAction.VIEW)
        return result.copy(rows = filtered)
    }

    private suspend fun resolveProfileId(authentication: AuthenticationContext): UUID? {
        val authenticated = authentication.principal() ?: return null
        val principal = authenticated.asPrincipal()
        return principal.primaryProfileId
            ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
    }
}

object WorkOpsSavedFiltersMutation

@TypeController
class SavedFilterMutationController(
    private val service: SavedFilterService,
    private val groupEvaluator: GroupEvaluator,
    private val profileService: ProfileService,
) : GraphQLController<WorkOpsSavedFiltersMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: SavedFilterInput): SavedFilter {
        val authenticated = authentication.principal()
            ?: error("createSavedFilter requires an authenticated principal")
        val principal = authenticated.asPrincipal()
        val profileId = principal.primaryProfileId
            ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
            ?: error("createSavedFilter requires a profile")
        return service.create(profileId, input)
    }

    @Field
    suspend fun update(
        authentication: AuthenticationContext,
        id: UUID,
        input: SavedFilterInput,
        expectedVersion: Long,
    ): SavedFilter {
        verifyFilterOwnership(authentication, id)
        return service.update(id, input, expectedVersion)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        verifyFilterOwnership(authentication, id)
        service.delete(id)
        return true
    }

    private suspend fun verifyFilterOwnership(authentication: AuthenticationContext, filterId: UUID) {
        val filter = service.getById(filterId) ?: error("saved filter not found")
        val authenticated = authentication.principal()
            ?: error("requires an authenticated principal")
        val principal = authenticated.asPrincipal()
        val profileId = principal.primaryProfileId
            ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
        if (filter.ownerProfileId != profileId && !groupEvaluator.hasAdminGroup(authentication)) {
            groupEvaluator.throwUnauthorized()
        }
    }
}
