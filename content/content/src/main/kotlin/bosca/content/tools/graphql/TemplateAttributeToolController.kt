package bosca.content.tools.graphql

import bosca.content.tools.model.TemplateAttributeTool
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@TypeController
class TemplateAttributeToolController : GraphQLController<TemplateAttributeTool> {

    @Field
    fun id(tool: TemplateAttributeTool): UUID {
        return tool.id
    }

    @Field
    fun key(tool: TemplateAttributeTool): String {
        return tool.key
    }

    @Field
    fun name(tool: TemplateAttributeTool): String {
        return tool.name
    }

    @Field
    fun description(tool: TemplateAttributeTool): String? {
        return tool.description
    }

    @Field
    fun query(tool: TemplateAttributeTool): String {
        return tool.query
    }

    @Field
    fun resultPath(tool: TemplateAttributeTool): String? {
        return tool.resultPath
    }

    @Field
    fun configuration(tool: TemplateAttributeTool): JsonElement? {
        return tool.configuration
    }
}
