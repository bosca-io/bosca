package bosca.content.attributes.graphql

import bosca.content.attributes.model.TemplateTool
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class TemplateToolController : GraphQLController<TemplateTool> {

    @Field
    fun id(tool: TemplateTool) = tool.id

    @Field
    fun name(tool: TemplateTool) = tool.name

    @Field
    fun description(tool: TemplateTool) = tool.description

    @Field
    fun query(tool: TemplateTool) = tool.query

    @Field
    fun resultPath(tool: TemplateTool) = tool.resultPath
}