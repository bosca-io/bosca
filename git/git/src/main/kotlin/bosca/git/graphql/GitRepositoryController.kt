package bosca.git.graphql

import bosca.git.model.Repository
import bosca.git.model.RepositoryConfiguration
import bosca.git.model.RepositoryContentType
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.slug.service.SlugService

/**
 * Resolves all fields on the [GitRepository] GraphQL type, mapping from the
 * [Repository] data class to the schema. Computed fields like [permissions]
 * require service calls and permission checks.
 */
@TypeController(type = "GitRepository")
class GitRepositoryController(
    private val repositoryService: RepositoryService,
    private val permissionEvaluator: RepositoryPermissionEvaluator,
    private val slugService: SlugService,
    private val application: BoscaApplication
) : GraphQLController<Repository> {

    @Field
    fun id(source: Repository): UUID = source.id

    @Field
    fun slug(source: Repository): String = source.slug

    @Field
    fun name(source: Repository): String = source.name

    @Field
    fun description(source: Repository): String? = source.description

    @Field
    fun ownerId(source: Repository): UUID = source.ownerId

    @Field
    fun visibility(source: Repository): Visibility = source.visibility

    @Field
    fun defaultBranch(source: Repository): String = source.defaultBranch

    @Field
    fun archived(source: Repository): Boolean = source.archived

    @Field
    fun deleted(source: Repository): Boolean = source.deleted

    @Field
    fun forkedFromId(source: Repository): UUID? = source.forkedFromId

    @Field
    fun contentType(source: Repository): RepositoryContentType? = source.contentType

    @Field
    fun diskSizeBytes(source: Repository): Long = source.diskSizeBytes

    @Field
    fun configuration(source: Repository): RepositoryConfiguration = source.configuration

    @Field
    fun created(source: Repository): OffsetDateTime = source.created

    @Field
    fun updated(source: Repository): OffsetDateTime = source.updated

    @Field
    suspend fun cloneUrl(source: Repository): String {
        val ownerSlug = slugService.getProfileSlug(source.ownerId) ?: return ""
        val gitUrl = application.config.propertyOrNull("git.url")?.getString()?.trimEnd('/') ?: return ""
        return "$gitUrl/$ownerSlug/${source.slug}.git"
    }

    @Field
    suspend fun permissions(authentication: AuthenticationContext?, source: Repository): List<Permission> {
        if (!permissionEvaluator.isAllowed(authentication, source, PermissionAction.MANAGE)) return emptyList()
        return repositoryService.getPermissions(source).map { Permission(it.groupId, it.action) }
    }

    /** Reports the caller's current repository execution grant for build controls. */
    @Field
    suspend fun canExecute(authentication: AuthenticationContext?, source: Repository): Boolean =
        permissionEvaluator.isAllowed(authentication, source, PermissionAction.EXECUTE)
}
