package bosca.content.metadata.graphql

import bosca.content.attributes.model.TemplateWorkflow
import bosca.content.attributes.model.TemplateTool
import bosca.content.metadata.model.ContainerRenderer
import bosca.content.metadata.model.DocumentTemplateContainer
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.tools.service.TemplateAttributeToolService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@TypeController
class DocumentTemplateContainerController(
    private val service: DocumentTemplateService,
    private val templateAttributeToolService: TemplateAttributeToolService,
    private val json: kotlinx.serialization.json.Json,
) : GraphQLController<DocumentTemplateContainer> {

    @Field
    fun id(template: DocumentTemplateContainer) = template.id

    @Field
    fun name(template: DocumentTemplateContainer) = template.name

    @Field
    fun description(template: DocumentTemplateContainer) = template.description

    @Field
    fun supplementaryKey(template: DocumentTemplateContainer) = template.supplementaryKey

    @Field
    fun type(template: DocumentTemplateContainer) = template.type

    @Field
    suspend fun tools(template: DocumentTemplateContainer): List<TemplateTool>? {
        val tools = template.tools?.let { json.decodeFromJsonElement(ListSerializer(TemplateTool.serializer()), it) }
        if (tools == null) return null

        return tools.map { tool ->
            if (tool.id != null) {
                val definedTool = templateAttributeToolService.get(tool.id!!)
                if (definedTool != null) {
                    return@map tool.copy(
                        name = definedTool.name,
                        description = definedTool.description,
                        query = definedTool.query,
                        resultPath = definedTool.resultPath
                    )
                }
            }
            tool
        }
    }

    @Field
    fun renderers(template: DocumentTemplateContainer): List<ContainerRenderer>? {
        return template.renderers?.let {
            json.decodeFromJsonElement(ListSerializer(ContainerRenderer.serializer()), it)
        }
    }

    @Field
    fun filters(template: DocumentTemplateContainer): List<String>? {
        return template.filters?.let {
            json.decodeFromJsonElement(ListSerializer(String.serializer()), it)
        }
    }

    @Field
    suspend fun workflows(template: DocumentTemplateContainer) =
        service.getTemplateAttributeWorkflows(template.metadataId, template.version, template.id)
            .map { TemplateWorkflow(it.workflowId, it.autoRun) }
}
