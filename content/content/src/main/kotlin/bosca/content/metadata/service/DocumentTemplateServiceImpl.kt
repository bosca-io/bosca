package bosca.content.metadata.service

import bosca.attributes.TemplateAttributeInput
import bosca.cache.ServiceCache
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.attributes.model.TemplateTool
import bosca.content.metadata.model.ContainerRenderer
import bosca.content.metadata.model.ContainerType
import bosca.content.metadata.model.DocumentTemplate
import bosca.content.metadata.model.DocumentTemplateAttribute
import bosca.content.metadata.model.DocumentTemplateAttributeWorkflow
import bosca.content.metadata.model.DocumentTemplateContainer
import bosca.content.metadata.model.DocumentTemplateContainerInput
import bosca.content.metadata.model.DocumentTemplateInput
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataCacheKeySerializer
import bosca.content.metadata.repository.DocumentTemplateAttributeRepository
import bosca.content.metadata.repository.DocumentTemplateAttributeWorkflowRepository
import bosca.content.metadata.repository.DocumentTemplateContainerRepository
import bosca.content.metadata.repository.DocumentTemplateRepository
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@ServiceImplementation
class DocumentTemplateServiceImpl(
    private val documentTemplateRepository: DocumentTemplateRepository,
    private val documentTemplateAttributeRepository: DocumentTemplateAttributeRepository,
    private val documentTemplateAttributeWorkflowRepository: DocumentTemplateAttributeWorkflowRepository,
    private val documentTemplateContainerRepository: DocumentTemplateContainerRepository,
    private val json: Json
) : DocumentTemplateService {

    private val documentTemplateCache = ServiceCache(
        "metadata:document:template",
        MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            // TODO: use version
            val templatesByMetadataId = documentTemplateRepository.getByMetadataIds(keys.map { it.id }).associateBy { it.metadataId }
            keys.forEach { key ->
                templatesByMetadataId[key.id]?.let { batch.setData(key, it) }
            }
        }
    ) {
        documentTemplateRepository.getByMetadataIdAndVersion(it.id, it.version ?: error("missing version"))
    }

    private val documentTemplateAttributeCache = ServiceCache<MetadataCacheKeyId, List<TemplateAttribute>>("metadata:document:template:attribute", MetadataCacheKeySerializer) {
        documentTemplateAttributeRepository.getByMetadataIdAndVersion(it.id, it.version ?: error("missing version"))
            .map { TemplateAttribute(it) }
    }

    private val documentTemplateAttributeWorkflowCache = ServiceCache<MetadataCacheKeyId, List<DocumentTemplateAttributeWorkflow>>("metadata:document:template:attribute:workflow", MetadataCacheKeySerializer) {
        documentTemplateAttributeWorkflowRepository.getByMetadataIdAndVersion(it.id, it.version ?: error("missing version"), it.key ?: error("missing key"))
    }

    private val documentTemplateContainerCache = ServiceCache<MetadataCacheKeyId, List<DocumentTemplateContainer>>("metadata:document:template:container", MetadataCacheKeySerializer) {
        documentTemplateContainerRepository.getByMetadataIdAndVersion(it.id, it.version ?: error("missing version"))
    }

    private suspend fun removeFromCache(id: UUID, version: Int? = null) {
        val id = MetadataCacheKeyId(id, version)
        documentTemplateCache.remove(id)
        documentTemplateAttributeCache.remove(id)
        documentTemplateContainerCache.remove(id)
        documentTemplateAttributeWorkflowCache.remove(id, keyPrefix = true)
    }

    override suspend fun getAll() = documentTemplateRepository.getAll()

    override suspend fun getTemplate(id: UUID, version: Int) = documentTemplateCache.get(MetadataCacheKeyId(id, version))

    override suspend fun addToBatch(batch: Batch<MetadataCacheKeyId, DocumentTemplate>) = documentTemplateCache.addToBatch(batch)

    override suspend fun getTemplateAttributes(id: UUID, version: Int): List<TemplateAttribute> = documentTemplateAttributeCache.get(MetadataCacheKeyId(id, version)) ?: emptyList()

    override suspend fun getTemplateContainers(id: UUID, version: Int) =
        documentTemplateContainerCache.get(MetadataCacheKeyId(id, version)) ?: emptyList()

    override suspend fun getTemplateAttributeWorkflows(id: UUID, version: Int, key: String) =
        documentTemplateAttributeWorkflowCache.get(MetadataCacheKeyId(id, version, key)) ?: emptyList()

    override suspend fun addAttribute(
        metadataId: UUID,
        version: Int,
        attribute: TemplateAttributeInput,
        sort: Int
    ) = transaction {
        // TODO: move the other attributes
        documentTemplateAttributeRepository.add(
            DocumentTemplateAttribute(
                metadataId = metadataId,
                version = version,
                key = attribute.key,
                name = attribute.name,
                description = attribute.description,
                supplementaryKey = attribute.supplementaryKey,
                configuration = attribute.configuration,
                type = attribute.type,
                ui = attribute.ui,
                list = attribute.list,
                sort = sort,
                tools = attribute.tools?.map {
                    TemplateTool(
                        id = it.id,
                        name = it.name,
                        description = it.description,
                        query = it.query,
                        resultPath = it.resultPath
                    )
                }?.let { json.encodeToJsonElement(it) }
            )
        )
        removeFromCache(metadataId, version)
    }

    override suspend fun deleteAttribute(
        metadataId: UUID,
        version: Int,
        key: String
    ) = transaction {
        documentTemplateAttributeRepository.deleteByMetadataIdAndVersionAndKey(metadataId, version, key)
        removeFromCache(metadataId, version)
    }

    override suspend fun setDefaultAttributes(
        metadataId: UUID,
        version: Int,
        attributes: JsonElement?
    ) = transaction {
        documentTemplateRepository.setDefaultAttributes(metadataId, version, attributes)
        removeFromCache(metadataId, version)
    }

    override suspend fun setAttributes(
        metadataId: UUID,
        version: Int,
        attributes: List<TemplateAttributeInput>
    ) = transaction {
        documentTemplateAttributeRepository.deleteByMetadataIdAndVersion(metadataId, version)
        attributes.forEachIndexed { index, attribute ->
            documentTemplateAttributeRepository.add(
                DocumentTemplateAttribute(
                    metadataId = metadataId,
                    version = version,
                    key = attribute.key,
                    name = attribute.name,
                    description = attribute.description,
                    supplementaryKey = attribute.supplementaryKey,
                    configuration = attribute.configuration,
                    type = attribute.type,
                    ui = attribute.ui,
                    list = attribute.list,
                    sort = index,
                    tools = attribute.tools?.map {
                        TemplateTool(
                            id = it.id,
                            name = it.name,
                            description = it.description,
                            query = it.query,
                            resultPath = it.resultPath
                        )
                    }?.let { json.encodeToJsonElement(it) }
                )
            )
        }
        removeFromCache(metadataId, version)
    }

    override suspend fun setConfiguration(
        metadataId: UUID,
        version: Int,
        configuration: JsonElement?
    ) {
        documentTemplateRepository.setConfiguration(metadataId, version, configuration)
        removeFromCache(metadataId, version)
    }

    override suspend fun setSchema(
        metadataId: UUID,
        version: Int,
        schema: JsonElement?
    ) {
        documentTemplateRepository.setSchema(metadataId, version, schema)
        removeFromCache(metadataId, version)
    }

    override suspend fun setContent(
        metadataId: UUID,
        version: Int,
        content: JsonElement?
    ) {
        documentTemplateRepository.setContent(metadataId, version, content)
        removeFromCache(metadataId, version)
    }

    override suspend fun addContainer(
        metadataId: UUID,
        version: Int,
        container: DocumentTemplateContainerInput,
        sort: Int
    ) {
        documentTemplateContainerRepository.add(
            DocumentTemplateContainer(
                metadataId = metadataId,
                version = version,
                id = container.id,
                name = container.name,
                description = container.description,
                type = container.containerType ?: ContainerType.STANDARD,
                supplementaryKey = container.supplementaryKey,
                sort = sort,
                tools = container.tools?.map {
                    TemplateTool(
                        id = it.id,
                        name = it.name,
                        description = it.description,
                        query = it.query,
                        resultPath = it.resultPath
                    )
                }?.let { json.encodeToJsonElement(it) },
                renderers = container.renderers?.map { ContainerRenderer(name = it.name, configuration = it.configuration) }?.let { json.encodeToJsonElement(it) },
                filters = container.filters?.let { json.encodeToJsonElement(it) }
            )
        )
        removeFromCache(metadataId, version)
    }

    override suspend fun deleteContainer(
        metadataId: UUID,
        version: Int,
        containerId: String,
    ) {
        documentTemplateContainerRepository.deleteByMetadataIdAndVersionAndId(metadataId, version, containerId)
        removeFromCache(metadataId, version)
    }

    override suspend fun setContainers(
        metadataId: UUID,
        version: Int,
        containers: List<DocumentTemplateContainerInput>
    ) {
        documentTemplateContainerRepository.deleteByMetadataIdAndVersion(metadataId, version)
        containers.forEachIndexed { index, container ->
            documentTemplateContainerRepository.add(
                DocumentTemplateContainer(
                    metadataId = metadataId,
                    version = version,
                    id = container.id,
                    name = container.name,
                    description = container.description,
                    type = container.containerType ?: ContainerType.STANDARD,
                    supplementaryKey = container.supplementaryKey,
                    sort = index,
                    tools = container.tools?.map {
                        TemplateTool(
                            id = it.id,
                            name = it.name,
                            description = it.description,
                            query = it.query,
                            resultPath = it.resultPath
                        )
                    }?.let { json.encodeToJsonElement(it) },
                    renderers = container.renderers?.map { ContainerRenderer(name = it.name, configuration = it.configuration) }?.let { json.encodeToJsonElement(it) },
                    filters = container.filters?.let { json.encodeToJsonElement(it) }
                )
            )
        }
        removeFromCache(metadataId, version)
    }

    override suspend fun saveTemplate(id: UUID, version: Int, template: DocumentTemplateInput) = transaction {
        documentTemplateRepository.add(
            id = id,
            version = version,
            defaultAttributes = template.defaultAttributes,
            configuration = template.configuration,
            schema = template.schema,
            content = template.content
        )
        documentTemplateAttributeRepository.deleteByMetadataIdAndVersion(id, version)
        template.attributes.forEachIndexed { index, attribute ->
            documentTemplateAttributeRepository.add(
                DocumentTemplateAttribute(
                    metadataId = id,
                    version = version,
                    key = attribute.key,
                    name = attribute.name,
                    description = attribute.description,
                    supplementaryKey = attribute.supplementaryKey,
                    configuration = attribute.configuration,
                    type = attribute.type,
                    ui = attribute.ui,
                    list = attribute.list,
                    sort = index,
                    tools = attribute.tools?.map {
                        TemplateTool(
                            id = it.id,
                            name = it.name,
                            description = it.description,
                            query = it.query,
                            resultPath = it.resultPath
                        )
                    }?.let { json.encodeToJsonElement(it) }
                )
            )
        }
        documentTemplateContainerRepository.deleteByMetadataIdAndVersion(id, version)
        template.containers.forEachIndexed { index, container ->
            documentTemplateContainerRepository.add(
                DocumentTemplateContainer(
                    metadataId = id,
                    version = version,
                    id = container.id,
                    name = container.name,
                    description = container.description,
                    type = container.containerType ?: ContainerType.STANDARD,
                    supplementaryKey = container.supplementaryKey,
                    sort = index,
                    tools = container.tools?.map {
                        TemplateTool(
                            id = it.id,
                            name = it.name,
                            description = it.description,
                            query = it.query,
                            resultPath = it.resultPath
                        )
                    }?.let { json.encodeToJsonElement(it) },
                    renderers = container.renderers?.map { ContainerRenderer(name = it.name, configuration = it.configuration) }?.let { json.encodeToJsonElement(it) },
                    filters = container.filters?.let { json.encodeToJsonElement(it) }
                )
            )
        }
        removeFromCache(id, version)
    }
}
