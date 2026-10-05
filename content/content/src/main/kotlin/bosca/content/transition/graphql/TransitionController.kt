package bosca.content.transition.graphql

import bosca.content.transition.model.Transition
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class TransitionController : GraphQLController<Transition> {

    @Field
    fun fromStateId(transition: Transition) = transition.fromStateId

    @Field
    fun toStateId(transition: Transition) = transition.toStateId

    @Field
    fun description(transition: Transition) = transition.description

    @Field
    fun configuration(transition: Transition) = transition.configuration

    @Field
    fun enterJobName(transition: Transition) = transition.enterJobName

    @Field
    fun exitJobName(transition: Transition) = transition.exitJobName
}
