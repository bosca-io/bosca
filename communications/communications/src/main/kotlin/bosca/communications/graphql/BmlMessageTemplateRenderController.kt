package bosca.communications.graphql

import bosca.communications.model.BmlMessageTemplateRender
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import kotlinx.serialization.json.JsonElement

@TypeController(type = "BmlMessageTemplateRender")
class BmlMessageTemplateRenderController : GraphQLController<BmlMessageTemplateRender> {

    @Field fun project(render: BmlMessageTemplateRender): String = render.project
    @Field fun templateKey(render: BmlMessageTemplateRender): String = render.templateKey
    @Field fun version(render: BmlMessageTemplateRender): String? = render.version
    @Field fun parameters(render: BmlMessageTemplateRender): JsonElement? = render.parameters
}
