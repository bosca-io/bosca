package bosca.gateway.controller

import bosca.gateway.model.GatewayConfig
import bosca.gateway.service.GatewayConfigService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

object GatewayNamespace

@TypeController
class GatewayNamespaceQueryController(
    private val configService: GatewayConfigService,
) : GraphQLController<GatewayNamespace> {
    @Field fun services() = GatewayServices
    @Field fun routes() = GatewayRoutes
    @Field suspend fun config(): GatewayConfig = configService.getConfig()
}

object GatewayMutationsRoot

@TypeController(type = "GatewayMutations")
class GatewayMutationController : GraphQLController<GatewayMutationsRoot> {
    @Field fun services() = GatewayServicesMutation
    @Field fun routes() = GatewayRoutesMutation
}
