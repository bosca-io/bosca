package bosca.ai.chat.graphql

import bosca.ai.kit.harness.KitContextReference
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class KitContextReferenceController : GraphQLController<KitContextReference> {
    @Field fun kind(value: KitContextReference) = value.kind
    @Field fun id(value: KitContextReference) = value.id
    @Field fun title(value: KitContextReference) = value.title
    @Field fun ref(value: KitContextReference) = value.ref
    @Field fun path(value: KitContextReference) = value.path
    @Field fun selected(value: KitContextReference) = value.selected
}
