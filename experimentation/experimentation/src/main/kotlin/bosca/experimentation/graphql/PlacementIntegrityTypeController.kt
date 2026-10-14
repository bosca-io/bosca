package bosca.experimentation.graphql

import bosca.experimentation.model.*
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Explicit fields for persisted PlacementIntegrity aggregates. */
@TypeController(type = "PlacementIntegrity")
class PlacementIntegrityTypeController : GraphQLController<PlacementIntegrity> {
    @Field fun expectedOnly(value: PlacementIntegrity): Long = value.expectedOnly
    @Field fun unexpectedOnly(value: PlacementIntegrity): Long = value.unexpectedOnly
    @Field fun both(value: PlacementIntegrity): Long = value.both
    @Field fun neither(value: PlacementIntegrity): Long = value.neither
}
