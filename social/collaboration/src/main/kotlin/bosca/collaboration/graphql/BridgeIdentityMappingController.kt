package bosca.collaboration.graphql

import bosca.collaboration.bridge.BridgeIdentityMapping
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * GraphQL field resolvers for [BridgeIdentityMapping]. The model is a
 * straight-through projection — every field maps to a property — but
 * Bosca's KSP-generated controllers require explicit `@Field` resolvers
 * so the type appears in the schema with stable getters.
 */
@TypeController
class BridgeIdentityMappingController : GraphQLController<BridgeIdentityMapping> {

    @Field
    fun id(mapping: BridgeIdentityMapping) = mapping.id

    @Field
    fun platform(mapping: BridgeIdentityMapping) = mapping.platform

    @Field
    fun externalUserId(mapping: BridgeIdentityMapping) = mapping.externalUserId

    @Field
    fun workspaceId(mapping: BridgeIdentityMapping) = mapping.workspaceId

    @Field
    fun profileId(mapping: BridgeIdentityMapping) = mapping.profileId

    @Field
    fun displayName(mapping: BridgeIdentityMapping) = mapping.displayName

    @Field
    fun email(mapping: BridgeIdentityMapping) = mapping.email
}
