package bosca.trait.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.trait.model.Trait

@TypeController
class TraitController : GraphQLController<Trait> {

    @Field
    fun id(trait: Trait) = trait.id

    @Field
    fun name(trait: Trait) = trait.name

    @Field
    fun description(trait: Trait) = trait.description

    @Field
    fun deleteWorkflowId(trait: Trait) = trait.deleteWorkflowId
}
