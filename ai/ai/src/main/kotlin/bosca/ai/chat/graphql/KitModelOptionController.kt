package bosca.ai.chat.graphql

import bosca.ai.kit.harness.KitModelOption
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class KitModelOptionController : GraphQLController<KitModelOption> {
    @Field fun value(option: KitModelOption) = option.value
    @Field fun name(option: KitModelOption) = option.name
}
