package bosca.experimentation.graphql

import bosca.experimentation.model.*
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Explicit fields for persisted GoalDiagnostics aggregates. */
@TypeController(type = "GoalDiagnostics")
class GoalDiagnosticsTypeController : GraphQLController<GoalDiagnostics> {
    @Field fun excludedPrincipals(value: GoalDiagnostics): Long = value.excludedPrincipals
    @Field fun excludedInstallations(value: GoalDiagnostics): Long = value.excludedInstallations
    @Field fun duplicateDeliveries(value: GoalDiagnostics): Long = value.duplicateDeliveries
    @Field fun outsideApplication(value: GoalDiagnostics): Long = value.outsideApplication
    @Field fun missingApplication(value: GoalDiagnostics): Long = value.missingApplication
}
