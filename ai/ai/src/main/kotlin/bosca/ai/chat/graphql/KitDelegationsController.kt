package bosca.ai.chat.graphql

import bosca.ai.kit.harness.KitDelegationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

@TypeController
class KitDelegationsController(private val service: KitDelegationService, private val groups: GroupEvaluator) : GraphQLController<KitDelegations> {
    @Field suspend fun settings(authentication: AuthenticationContext, sessionId: UUID): bosca.ai.kit.harness.KitDelegationPolicy {
        groups.verifyHasScope(authentication, "ai:delegate")
        return service.settings(authentication, sessionId)
    }
    @Field suspend fun get(authentication: AuthenticationContext, sessionId: UUID, id: UUID): bosca.ai.kit.harness.KitDelegation? {
        groups.verifyHasScope(authentication, "ai:delegate")
        return service.get(authentication, sessionId, id)
    }
    @Field suspend fun list(authentication: AuthenticationContext, sessionId: UUID, offset: Long = 0, limit: Int = 20): List<bosca.ai.kit.harness.KitDelegation> {
        groups.verifyHasScope(authentication, "ai:delegate")
        return service.list(authentication, sessionId, offset, limit)
    }
}
