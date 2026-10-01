package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsScriptBinding
import bosca.analytics.service.AnalyticsScriptBindingService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Marker type for the `Analytics.scriptBindings` GraphQL field — reads over analytics script bindings. */
object AnalyticsScriptBindings

/**
 * Read-side GraphQL controller for analytics script bindings. Requires the `analytics.manager` group
 * or platform admin, matching the error-tracking controllers.
 */
@TypeController
class AnalyticsScriptBindingsController(
    private val bindingService: AnalyticsScriptBindingService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AnalyticsScriptBindings> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<AnalyticsScriptBinding> {
        AnalyticsScriptBindingsAuthorization.verifyCanRead(groupEvaluator, authentication)
        return bindingService.list()
    }

    @Field
    suspend fun binding(authentication: AuthenticationContext, id: UUID): AnalyticsScriptBinding? {
        AnalyticsScriptBindingsAuthorization.verifyCanRead(groupEvaluator, authentication)
        return bindingService.get(id)
    }
}
