package bosca.ai.chat.graphql

import bosca.ai.kit.harness.KitDelegation
import bosca.ai.kit.harness.KitDelegationStatus
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class KitDelegationController : GraphQLController<KitDelegation> {
    @Field fun id(value: KitDelegation) = value.id
    @Field fun parentSessionId(value: KitDelegation) = value.parentSessionId
    @Field fun parentTurnId(value: KitDelegation) = value.parentTurnId
    @Field fun childSessionId(value: KitDelegation) = value.childSessionId
    @Field fun profile(value: KitDelegation) = value.input.profile
    @Field fun depth(value: KitDelegation) = value.depth
    @Field fun runtimeSeconds(value: KitDelegation) = value.input.runtimeSeconds
    @Field fun deadline(value: KitDelegation) = value.deadline
    @Field fun status(value: KitDelegation) = value.status
    @Field fun partial(value: KitDelegation) = value.status != KitDelegationStatus.COMPLETED && value.result != null
    @Field fun result(value: KitDelegation) = value.result
    @Field fun failure(value: KitDelegation) = value.failure
}
