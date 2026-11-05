package bosca.ai.chat.graphql

import bosca.ai.kit.harness.KitMcpOption
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class KitMcpOptionController : GraphQLController<KitMcpOption> {
    @Field fun key(option: KitMcpOption) = option.key
    @Field fun name(option: KitMcpOption) = option.name
    @Field fun required(option: KitMcpOption) = option.required
}
