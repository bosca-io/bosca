package bosca.content.metadata.graphql

import bosca.content.metadata.model.ContainerRenderer
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class ContainerRendererController : GraphQLController<ContainerRenderer> {

    @Field
    fun name(renderer: ContainerRenderer) = renderer.name

    @Field
    fun configuration(renderer: ContainerRenderer) = renderer.configuration
}
