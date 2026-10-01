package bosca.gateway.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Declares the GraphQL SDL fragments that compose the Gateway API surface.
 *
 * Each `@Schema` resource resolves against `src/main/resources/graphql/`;
 * the KSP-generated `GatewaySchemaRegistrar` reads each file, concatenates
 * the contents, and feeds the result to Bosca's central
 * [bosca.graphql.SchemaRegistry]. Without this declaration the `.graphqls`
 * files sit on disk but never reach the runtime — every mutation would
 * fail with `Unknown type 'GatewayInput'` because the schema doesn't
 * know about the gateway namespace.
 */
@Schemas
interface SchemaRegistrar {

    /** Namespace types + extensions to Query/Mutation. */
    @Schema("gateway/gateway.graphqls")
    val gateway: String

    /** [bosca.gateway.model.Gateway] type, input, and CRUD mutations. */
    @Schema("gateway/service.graphqls")
    val service: String

    /** [bosca.gateway.model.GatewayRoute] type, input, and CRUD mutations. */
    @Schema("gateway/route.graphqls")
    val route: String
}
