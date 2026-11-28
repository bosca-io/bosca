package bosca.ai.chat.graphql

import bosca.ai.kit.harness.*
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

@TypeController
class KitDelegationsMutationController(private val service: KitDelegationService, private val groups: GroupEvaluator) : GraphQLController<KitDelegationsMutation> {
    @Field suspend fun start(authentication: AuthenticationContext, sessionId: UUID, input: KitDelegationInput): KitDelegation {
        groups.verifyHasScope(authentication, "ai:delegate")
        return service.start(authentication, sessionId, input)
    }
    @Field suspend fun wait(authentication: AuthenticationContext, sessionId: UUID, id: UUID): KitDelegation {
        groups.verifyHasScope(authentication, "ai:delegate")
        return service.wait(authentication, sessionId, id)
    }
    @Field suspend fun cancel(authentication: AuthenticationContext, sessionId: UUID, id: UUID): KitDelegation {
        groups.verifyHasScope(authentication, "ai:delegate")
        return service.cancel(authentication, sessionId, id)
    }
    @Field suspend fun submit(authentication: AuthenticationContext, sessionId: UUID, output: KitDelegationResult): KitDelegation {
        groups.verifyHasScope(authentication, "ai:delegate")
        return service.submit(authentication, sessionId, output)
    }
}
