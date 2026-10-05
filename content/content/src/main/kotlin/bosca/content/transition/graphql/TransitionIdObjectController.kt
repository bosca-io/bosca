package bosca.content.transition.graphql

import bosca.content.transition.model.TransitionIdObject
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class TransitionIdObjectController : GraphQLController<TransitionIdObject> {

    @Field
    fun fromStateId(id: TransitionIdObject) = id.fromStateId

    @Field
    fun toStateId(id: TransitionIdObject) = id.toStateId
}