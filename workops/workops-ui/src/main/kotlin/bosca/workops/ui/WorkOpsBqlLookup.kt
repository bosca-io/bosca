package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.workops.ui.generated.WorkOpsBql
import bosca.workops.ui.graphql.WorkOpsValidateBql
import bosca.workops.ui.graphql.WorkOpsValidateSpecBql

/** Live query feedback uses the same WorkOps validation boundary as submitted task searches. */
class WorkOpsBqlLookup : WorkOpsBql {
    override suspend fun validate(gql: GraphQLClient, source: String): List<Map<String, String>> =
        gql.execute(WorkOpsValidateBql, WorkOpsValidateBql.Variables(source)).workOps.savedFilters.validateBql.errors.map {
            mapOf("message" to it.message, "start" to it.start.toString(), "end" to it.end.toString(), "hint" to it.hint.orEmpty(),
                "description" to WorkOpsBqlDiagnostic(it.message, it.start, it.end, it.hint.orEmpty()).description)
        }

    override suspend fun validateSpec(gql: GraphQLClient, source: String): List<Map<String, String>> =
        gql.execute(WorkOpsValidateSpecBql, WorkOpsValidateSpecBql.Variables(source)).workOps.savedFilters.validateSpecBql.errors.map {
            mapOf("message" to it.message, "start" to it.start.toString(), "end" to it.end.toString(), "hint" to it.hint.orEmpty(),
                "description" to WorkOpsBqlDiagnostic(it.message, it.start, it.end, it.hint.orEmpty()).description)
        }
}
