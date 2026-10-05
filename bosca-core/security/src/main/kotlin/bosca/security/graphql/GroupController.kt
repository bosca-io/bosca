package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Group



@TypeController
class GroupController : GraphQLController<Group> {

    @Field
    fun id(model: Group) = model.id

    @Field
    fun type(model: Group) = model.type

    @Field
    fun name(model: Group) = model.name

    @Field
    fun description(model: Group) = model.description

}