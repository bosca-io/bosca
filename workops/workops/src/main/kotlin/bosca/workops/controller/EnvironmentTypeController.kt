package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentType
import bosca.workops.service.EnvironmentService
import bosca.workops.service.EnvironmentTypeService
import bosca.workops.service.EnvironmentPermissionEvaluator

// ── Marker objects ────────────────────────────────────────────────────

@TypeController(type = "WorkOpsEnvironment")
class EnvironmentTypeController(
    private val envService: EnvironmentService,
    private val envTypeService: EnvironmentTypeService,
    private val permissionEvaluator: EnvironmentPermissionEvaluator,
) : GraphQLController<Environment> {
    @Field fun id(e: Environment) = e.id
    @Field fun programId(e: Environment) = e.programId
    @Field fun key(e: Environment) = e.key
    @Field fun name(e: Environment) = e.name
    @Field fun description(e: Environment) = e.description
    @Field fun displayOrder(e: Environment) = e.displayOrder
    @Field suspend fun promotionSourceIds(e: Environment) = envService.promotionSourceIds(e.id)
    @Field fun requiresApproval(e: Environment) = e.requiresApproval
    @Field fun autoPromote(e: Environment) = e.autoPromote
    @Field fun typeId(e: Environment) = e.typeId
    @Field suspend fun type(e: Environment): EnvironmentType =
        envTypeService.getById(e.typeId) ?: error("Environment ${e.id} references a missing environment type ${e.typeId}")
    @Field fun targetType(e: Environment) = e.targetType
    @Field fun targetRef(e: Environment) = e.targetRef
    @Field fun ephemeral(e: Environment) = e.ephemeral
    @Field fun version(e: Environment) = e.version
    @Field
    suspend fun permissions(authentication: AuthenticationContext, e: Environment): List<EntityPermission> {
        if (!permissionEvaluator.isAllowed(authentication, e, PermissionAction.MANAGE)) return emptyList()
        return envService.getPermissions(e)
    }
}
