package bosca.content.attributes.graphql

import bosca.content.attributes.model.TemplateAttribute
import bosca.content.attributes.model.TemplateTool
import bosca.content.attributes.model.TemplateWorkflow
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.tools.service.TemplateAttributeToolService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import kotlinx.serialization.builtins.ListSerializer

@TypeController
class TemplateAttributeController(
    private val documentService: DocumentTemplateService,
    private val collectionTemplateService: CollectionTemplateService,
    private val dataTemplateService: DataTemplateService,
    private val templateAttributeToolService: TemplateAttributeToolService,
    private val json: kotlinx.serialization.json.Json
) : GraphQLController<TemplateAttribute> {

    @Field
    fun name(attribute: TemplateAttribute) = attribute.name

    @Field
    fun key(attribute: TemplateAttribute) = attribute.key

    @Field
    fun description(attribute: TemplateAttribute) = attribute.description

    @Field
    fun supplementaryKey(attribute: TemplateAttribute) = attribute.supplementaryKey

    @Field
    fun configuration(attribute: TemplateAttribute) = attribute.configuration

    @Field
    fun type(attribute: TemplateAttribute) = attribute.type

    @Field
    fun location(attribute: TemplateAttribute) = attribute.location

    @Field
    fun ui(attribute: TemplateAttribute) = attribute.ui

    @Field
    fun list(attribute: TemplateAttribute) = attribute.list

    @Field
    suspend fun tools(attribute: TemplateAttribute): List<TemplateTool>? {
        val tools = attribute.tools?.let { json.decodeFromJsonElement(ListSerializer(TemplateTool.serializer()), it) }
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
    suspend fun workflows(attribute: TemplateAttribute): List<TemplateWorkflow> {
        attribute.documentAttribute?.let { documentAttribute ->
            return documentService.getTemplateAttributeWorkflows(
                documentAttribute.metadataId,
                documentAttribute.version,
                documentAttribute.key
            ).map { TemplateWorkflow(it.workflowId, it.autoRun) }
        }
        attribute.collectionAttribute?.let { collectionAttribute ->
            return collectionTemplateService.getCollectionTemplateAttributeWorkflows(
                collectionAttribute.metadataId,
                collectionAttribute.version,
                collectionAttribute.key
            ).map { TemplateWorkflow(it.workflowId, it.autoRun) }
        }
        attribute.dataAttribute?.let { dataAttribute ->
            return dataTemplateService.getTemplateAttributeWorkflows(
                dataAttribute.metadataId,
                dataAttribute.version,
                dataAttribute.key
            ).map { TemplateWorkflow(it.workflowId, it.autoRun) }
        }
        return emptyList()
    }
}