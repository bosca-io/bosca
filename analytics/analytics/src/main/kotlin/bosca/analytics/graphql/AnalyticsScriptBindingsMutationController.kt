package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsScriptBinding
import bosca.analytics.service.AnalyticsScriptBindingService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Marker type for the `AnalyticsMutation.scriptBindings` GraphQL field — writes over analytics script bindings. */
object AnalyticsScriptBindingsMutation

/**
 * Write-side GraphQL controller for analytics script bindings. Requires the `analytics.manager`
 * group or platform admin.
 */
@TypeController
class AnalyticsScriptBindingsMutationController(
    private val bindingService: AnalyticsScriptBindingService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AnalyticsScriptBindingsMutation> {

    @Field
    suspend fun add(
        authentication: AuthenticationContext,
        scriptId: UUID,
        transform: Boolean? = null,
        enabled: Boolean? = null,
        ordinal: Int? = null,
    ): AnalyticsScriptBinding {
        AnalyticsScriptBindingsAuthorization.verifyCanManage(groupEvaluator, authentication)
        return bindingService.add(
            scriptId = scriptId,
            transform = transform ?: true,
            enabled = enabled ?: true,
            ordinal = ordinal ?: 0,
        )
    }

    @Field
    suspend fun update(
        authentication: AuthenticationContext,
        id: UUID,
        scriptId: UUID,
        transform: Boolean? = null,
        enabled: Boolean? = null,
        ordinal: Int? = null,
    ): AnalyticsScriptBinding {
        AnalyticsScriptBindingsAuthorization.verifyCanManage(groupEvaluator, authentication)
        return bindingService.update(
            id = id,
            scriptId = scriptId,
            transform = transform ?: true,
            enabled = enabled ?: true,
            ordinal = ordinal ?: 0,
        )
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        AnalyticsScriptBindingsAuthorization.verifyCanManage(groupEvaluator, authentication)
        bindingService.delete(id)
        return true
    }
}
