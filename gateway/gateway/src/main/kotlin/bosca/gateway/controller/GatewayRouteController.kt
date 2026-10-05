package bosca.gateway.controller

import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayNotFoundException
import bosca.gateway.model.GatewayRoute
import bosca.gateway.model.GatewayRouteInput
import bosca.gateway.service.GatewayPermissionEvaluator
import bosca.gateway.service.GatewayRouteService
import bosca.gateway.service.GatewayService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

@TypeController(type = "GatewayRoute")
class GatewayRouteTypeController(
    private val gatewayService: GatewayService,
) : GraphQLController<GatewayRoute> {

    @Field fun id(r: GatewayRoute) = r.id
    @Field fun gatewayId(r: GatewayRoute) = r.gatewayId
    @Field fun pathPattern(r: GatewayRoute) = r.pathPattern
    @Field fun hosts(r: GatewayRoute) = r.hosts
    @Field fun authMethod(r: GatewayRoute) = r.authMethod
    @Field fun stripPrefix(r: GatewayRoute) = r.stripPrefix
    @Field fun readGroups(r: GatewayRoute) = r.readGroups
    @Field fun writeGroups(r: GatewayRoute) = r.writeGroups
    @Field fun injectHeaders(r: GatewayRoute) = r.injectHeaders
    @Field fun sortOrder(r: GatewayRoute) = r.sortOrder
    @Field fun enabled(r: GatewayRoute) = r.enabled
    @Field fun createdAt(r: GatewayRoute) = r.createdAt
    @Field fun modifiedAt(r: GatewayRoute) = r.modifiedAt
    @Field fun version(r: GatewayRoute) = r.version

    @Field
    suspend fun gateway(r: GatewayRoute): Gateway? = gatewayService.getById(r.gatewayId)
}

object GatewayRoutes

/**
 * Route queries filter results through the parent [Gateway]'s VIEW
 * permission. Routes are not visible to callers who cannot see the
 * owning gateway — exposing them would leak upstream URLs and the
 * per-route group-gating configuration.
 */
@TypeController
class GatewayRoutesQueryController(
    private val service: GatewayRouteService,
    private val gatewayService: GatewayService,
    private val permissionEvaluator: GatewayPermissionEvaluator,
) : GraphQLController<GatewayRoutes> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<GatewayRoute> {
        val routes = service.listAll()
        if (routes.isEmpty()) return emptyList()
        // Batch the parent-gateway lookup to avoid N+1 round-trips
        // when routes span many gateways. `gatewayService.getByIds`
        // returns at most one row per distinct parent.
        val parents = gatewayService.getByIds(routes.map { it.gatewayId }.distinct())
        val visibleGatewayIds = permissionEvaluator
            .filterAllowed(authentication, parents, PermissionAction.VIEW)
            .mapTo(HashSet()) { it.id }
        return routes.filter { visibleGatewayIds.contains(it.gatewayId) }
    }

    @Field
    suspend fun route(authentication: AuthenticationContext, id: UUID): GatewayRoute? {
        val route = service.getById(id) ?: return null
        val gateway = gatewayService.getById(route.gatewayId) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, gateway, PermissionAction.VIEW)) route else null
    }

    @Field
    suspend fun byGateway(authentication: AuthenticationContext, gatewayId: UUID): List<GatewayRoute> {
        val gateway = gatewayService.getById(gatewayId)
            ?: throw GatewayNotFoundException("Gateway", gatewayId.toString())
        permissionEvaluator.verifyAllowed(authentication, gateway, PermissionAction.VIEW)
        return service.listByGatewayId(gatewayId)
    }
}

object GatewayRoutesMutation

@TypeController
class GatewayRoutesMutationController(
    private val service: GatewayRouteService,
    private val gatewayService: GatewayService,
    private val permissionEvaluator: GatewayPermissionEvaluator,
) : GraphQLController<GatewayRoutesMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: GatewayRouteInput): GatewayRoute {
        val gateway = gatewayService.getById(input.gatewayId)
            ?: throw GatewayNotFoundException("Gateway", input.gatewayId.toString())
        permissionEvaluator.verifyAllowed(authentication, gateway, PermissionAction.EDIT)
        return service.create(input)
    }

    @Field
    suspend fun update(
        authentication: AuthenticationContext,
        id: UUID,
        input: GatewayRouteInput,
        expectedVersion: Long,
    ): GatewayRoute {
        val route = service.getById(id) ?: throw GatewayNotFoundException("GatewayRoute", id.toString())
        val gateway = gatewayService.getById(route.gatewayId)
            ?: throw GatewayNotFoundException("Gateway", route.gatewayId.toString())
        permissionEvaluator.verifyAllowed(authentication, gateway, PermissionAction.EDIT)
        // Block reparenting unless the caller also owns the target gateway.
        if (input.gatewayId != route.gatewayId) {
            val target = gatewayService.getById(input.gatewayId)
                ?: throw GatewayNotFoundException("Gateway", input.gatewayId.toString())
            permissionEvaluator.verifyAllowed(authentication, target, PermissionAction.EDIT)
        }
        return service.update(id, input, expectedVersion)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): GatewayRoute {
        val route = service.getById(id) ?: throw GatewayNotFoundException("GatewayRoute", id.toString())
        val gateway = gatewayService.getById(route.gatewayId)
            ?: throw GatewayNotFoundException("Gateway", route.gatewayId.toString())
        permissionEvaluator.verifyAllowed(authentication, gateway, PermissionAction.MANAGE)
        return service.delete(id, expectedVersion)
    }

    @Field
    suspend fun toggleEnabled(
        authentication: AuthenticationContext,
        id: UUID,
        enabled: Boolean,
        expectedVersion: Long,
    ): GatewayRoute {
        val route = service.getById(id) ?: throw GatewayNotFoundException("GatewayRoute", id.toString())
        val gateway = gatewayService.getById(route.gatewayId)
            ?: throw GatewayNotFoundException("Gateway", route.gatewayId.toString())
        permissionEvaluator.verifyAllowed(authentication, gateway, PermissionAction.EDIT)
        return service.toggleEnabled(id, enabled, expectedVersion)
    }
}
