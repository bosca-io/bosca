package bosca.ai.chat.graphql

import bosca.ai.kit.harness.KitDelegationResult
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class KitDelegationResultController : GraphQLController<KitDelegationResult> {
    @Field fun summary(value: KitDelegationResult) = value.summary
    @Field fun artifacts(value: KitDelegationResult) = value.artifacts
}
