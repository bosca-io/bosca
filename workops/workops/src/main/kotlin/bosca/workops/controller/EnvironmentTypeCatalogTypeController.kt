package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.environment.EnvironmentType

// ── Marker objects ────────────────────────────────────────────────────

/** Resolves the `WorkOpsEnvironmentType` GraphQL type — the global environment-type catalog entry. */
@TypeController(type = "WorkOpsEnvironmentType")
class EnvironmentTypeCatalogTypeController : GraphQLController<EnvironmentType> {
    @Field fun id(t: EnvironmentType) = t.id
    @Field fun name(t: EnvironmentType) = t.name
    @Field fun description(t: EnvironmentType) = t.description
    @Field fun displayOrder(t: EnvironmentType) = t.displayOrder
    @Field fun version(t: EnvironmentType) = t.version
}
