package bosca.gateway.controller

import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayGroups
import bosca.gateway.model.GatewayInput
import bosca.gateway.model.GatewayNotFoundException
import bosca.gateway.model.GatewayRoute
import bosca.gateway.repository.GatewayPermissionRepository
import bosca.gateway.service.GatewayPermissionEvaluator
import bosca.gateway.service.GatewayRouteService
import bosca.gateway.service.GatewayService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

@TypeController(type = "Gateway")
class GatewayTypeController(
    private val routeService: GatewayRouteService,
    private val gatewayService: GatewayService,
    private val permissionEvaluator: GatewayPermissionEvaluator,
) : GraphQLController<Gateway> {

    @Field fun id(g: Gateway) = g.id
    @Field fun name(g: Gateway) = g.name
    @Field fun url(g: Gateway) = g.url
    @Field fun healthCheckPath(g: Gateway) = g.healthCheckPath
    @Field fun healthCheckIntervalSecs(g: Gateway) = g.healthCheckIntervalSecs
    @Field fun connectTimeoutSecs(g: Gateway) = g.connectTimeoutSecs
    @Field fun requestTimeoutSecs(g: Gateway) = g.requestTimeoutSecs
    @Field fun poolMaxIdle(g: Gateway) = g.poolMaxIdle
    @Field fun poolIdleTimeoutSecs(g: Gateway) = g.poolIdleTimeoutSecs
    @Field fun enabled(g: Gateway) = g.enabled
    @Field fun healthStatus(g: Gateway) = g.healthStatus
    @Field fun healthStatusChangedAt(g: Gateway) = g.healthStatusChangedAt
    @Field fun healthStatusReason(g: Gateway) = g.healthStatusReason
    @Field fun createdAt(g: Gateway) = g.createdAt
    @Field fun modifiedAt(g: Gateway) = g.modifiedAt
    @Field fun version(g: Gateway) = g.version

    @Field
    suspend fun routes(g: Gateway): List<GatewayRoute> =
        routeService.listByGatewayId(g.id)

    @Field
    suspend fun permissions(authentication: AuthenticationContext, g: Gateway): List<EntityPermission> {
        if (!permissionEvaluator.isAllowed(authentication, g, PermissionAction.MANAGE)) return emptyList()
        return gatewayService.getPermissions(g)
    }
}

object GatewayServices

@TypeController
class GatewayServicesQueryController(
    private val service: GatewayService,
    private val permissionEvaluator: GatewayPermissionEvaluator,
) : GraphQLController<GatewayServices> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Gateway> =
        permissionEvaluator.filterAllowed(authentication, service.listAll(), PermissionAction.VIEW)

    @Field
    suspend fun gateway(authentication: AuthenticationContext, id: UUID): Gateway? {
        val gateway = service.getById(id) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, gateway, PermissionAction.VIEW)) gateway else null
    }

    @Field
    suspend fun gatewayByName(authentication: AuthenticationContext, name: String): Gateway? {
        val gateway = service.getByName(name) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, gateway, PermissionAction.VIEW)) gateway else null
    }
}

object GatewayServicesMutation

/**
 * Mutations on the [Gateway] entity. Two privilege tiers:
 *
 * 1. **[GatewayGroups.ADMIN] membership** is required to `create` a
 *    new gateway OR to change the routing-sensitive fields (`url`,
 *    `name`) of an existing gateway. An EDIT-holder cannot repoint
 *    an upstream URL — that would let them silently exfiltrate
 *    traffic from any caller who can already reach the gateway.
 *
 * 2. Per-entity **EDIT / MANAGE** permission via [GatewayPermissionEvaluator]
 *    is required for everything else: tweaking timeouts, toggling
 *    enabled state, deleting, and managing per-gateway ACL entries.
 */
@TypeController
class GatewayServicesMutationController(
    private val service: GatewayService,
    private val permissionEvaluator: GatewayPermissionEvaluator,
    private val permissionRepository: GatewayPermissionRepository,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<GatewayServicesMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: GatewayInput): Gateway {
        groupEvaluator.verifyHasGroup(authentication, GatewayGroups.ADMIN)
        return service.create(input)
    }

    @Field
    suspend fun update(
        authentication: AuthenticationContext,
        id: UUID,
        input: GatewayInput,
        expectedVersion: Long,
    ): Gateway {
        val gateway = service.getById(id) ?: throw GatewayNotFoundException("Gateway", id.toString())
        permissionEvaluator.verifyAllowed(authentication, gateway, PermissionAction.EDIT)
        // Changing the upstream URL or name is a privilege-escalation
        // surface (silent traffic exfiltration); require gateway-admin
        // in addition to per-entity EDIT for those specific fields.
        if (gateway.url != input.url || gateway.name != input.name) {
            groupEvaluator.verifyHasGroup(authentication, GatewayGroups.ADMIN)
        }
        return service.update(id, input, expectedVersion)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Gateway {
        val gateway = service.getById(id) ?: throw GatewayNotFoundException("Gateway", id.toString())
        permissionEvaluator.verifyAllowed(authentication, gateway, PermissionAction.MANAGE)
        return service.delete(id, expectedVersion)
    }

    @Field
    suspend fun toggleEnabled(
        authentication: AuthenticationContext,
        id: UUID,
        enabled: Boolean,
        expectedVersion: Long,
    ): Gateway {
        val gateway = service.getById(id) ?: throw GatewayNotFoundException("Gateway", id.toString())
        permissionEvaluator.verifyAllowed(authentication, gateway, PermissionAction.EDIT)
        return service.toggleEnabled(id, enabled, expectedVersion)
    }

    @Field
    suspend fun addPermission(
        authentication: AuthenticationContext,
        id: UUID,
        groupId: UUID,
        action: PermissionAction,
    ): Boolean {
        val gateway = service.getById(id) ?: throw GatewayNotFoundException("Gateway", id.toString())
        permissionEvaluator.verifyAllowed(authentication, gateway, PermissionAction.MANAGE)
        val grantedBy = authentication.principal()?.id
            ?: error("addPermission requires an authenticated principal")
        permissionRepository.add(id, groupId, action, grantedBy)
        return true
    }

    @Field
    suspend fun removePermission(
        authentication: AuthenticationContext,
        id: UUID,
        groupId: UUID,
        action: PermissionAction,
    ): Boolean {
        val gateway = service.getById(id) ?: throw GatewayNotFoundException("Gateway", id.toString())
        permissionEvaluator.verifyAllowed(authentication, gateway, PermissionAction.MANAGE)
        permissionRepository.delete(id, groupId, action)
        return true
    }
}
