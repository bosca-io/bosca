package bosca.ai.chat.graphql

import bosca.ai.kit.harness.KitContextStatus
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class KitContextStatusController : GraphQLController<KitContextStatus> {
    @Field fun sources(value: KitContextStatus) = value.sources
    @Field fun integrations(value: KitContextStatus) = value.integrations
    @Field fun notices(value: KitContextStatus) = value.notices
    @Field fun selectionCount(value: KitContextStatus) = value.selectionCount
}
