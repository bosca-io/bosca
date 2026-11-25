package bosca.ai.chat.graphql

import bosca.ai.kit.harness.KitDelegationPolicy
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class KitDelegationPolicyController : GraphQLController<KitDelegationPolicy> {
    @Field fun profiles(value: KitDelegationPolicy) = value.profiles
    @Field fun maxDepth(value: KitDelegationPolicy) = value.maxDepth
    @Field fun maxActive(value: KitDelegationPolicy) = value.maxActive
    @Field fun maxChildren(value: KitDelegationPolicy) = value.maxChildren
    @Field fun maxRuntimeSeconds(value: KitDelegationPolicy) = value.maxRuntimeSeconds
    @Field fun totalRuntimeSeconds(value: KitDelegationPolicy) = value.totalRuntimeSeconds
}
