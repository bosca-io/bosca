package bosca.content.state.graphql


import bosca.content.state.model.State
import bosca.content.state.model.WorkflowStateType
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController


@TypeController("WorkflowState")
class StateController : GraphQLController<State> {

    @Field
    fun id(state: State) = state.id

    @Field
    fun name(state: State) = state.name

    @Field
    fun description(state: State) = state.description

    @Field
    fun configuration(state: State) = state.configuration

    @Field
    fun type(state: State): WorkflowStateType = state.type

    @Field
    fun jobName(state: State) = state.jobName
}
