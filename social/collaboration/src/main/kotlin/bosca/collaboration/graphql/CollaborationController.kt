package bosca.collaboration.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Marker for the `collaboration` namespace on the root [bosca.graphql.QueryRoot].
 * Bridge and federation administration both live under this namespace so the
 * top-level Query stays a directory of feature areas rather than a flat list
 * of every administrative sub-tree.
 */
object Collaboration

/**
 * Marker for the `collaboration` namespace on the root mutation. Mirrors
 * [Collaboration] so admin mutations live alongside their query counterparts.
 */
object CollaborationMutation

@TypeController
class CollaborationController : GraphQLController<Collaboration> {

    /** Bridge administration: bindings, identity mappings, bot-token rotation. */
    @Field
    fun bridge() = Bridge

    /** Federation administration: peers and channel links between Bosca instances. */
    @Field
    fun federation() = Federation
}

@TypeController
class CollaborationMutationController : GraphQLController<CollaborationMutation> {

    /** Bridge mutations: create/deactivate bindings, rotate tokens, map identities. */
    @Field
    fun bridge() = BridgeMutation

    /** Federation mutations: register/deactivate peers, federate channels, rotate secrets. */
    @Field
    fun federation() = FederationMutation
}
