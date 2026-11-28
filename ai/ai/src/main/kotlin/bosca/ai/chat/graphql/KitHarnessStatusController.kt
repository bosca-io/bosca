package bosca.ai.chat.graphql

import bosca.ai.kit.harness.KitHarnessStatus
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class KitHarnessStatusController : GraphQLController<KitHarnessStatus> {
    @Field fun state(status: KitHarnessStatus) = status.state
    @Field fun cancellationRequested(status: KitHarnessStatus) = status.cancellationRequested
    @Field fun failure(status: KitHarnessStatus) = status.failure
}
