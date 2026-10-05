package bosca.content.metadata.service

import bosca.attributes.TemplateAttributeInput
import bosca.cache.ServiceCache
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.attributes.model.TemplateTool
import bosca.content.metadata.model.DataTemplate
import bosca.content.metadata.model.DataType
import bosca.content.metadata.model.DataTemplateAttribute
import bosca.content.metadata.model.DataTemplateAttributeWorkflow
import bosca.content.metadata.model.DataTemplateInput
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataCacheKeySerializer
import bosca.content.metadata.repository.DataTemplateAttributeRepository
import bosca.content.metadata.repository.DataTemplateAttributeWorkflowRepository
import bosca.content.metadata.repository.DataTemplateRepository
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@ServiceImplementation
class DataTemplateServiceImpl(
    private val dataTemplateRepository: DataTemplateRepository,
    private val dataTemplateAttributeRepository: DataTemplateAttributeRepository,
    private val dataTemplateAttributeWorkflowRepository: DataTemplateAttributeWorkflowRepository,
    private val json: Json
) : DataTemplateService {

    private val dataTemplateCache = ServiceCache(
        "metadata:data:template",
        MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val templatesByMetadataId = dataTemplateRepository.getByMetadataIds(keys.map { it.id }).associateBy { it.metadataId }
            keys.forEach { key ->
                templatesByMetadataId[key.id]?.let { batch.setData(key, it) }
            }
        }
    ) {
        dataTemplateRepository.getByMetadataIdAndVersion(it.id, it.version ?: error("missing version"))
    }

    private val dataTemplateAttributeCache = ServiceCache<MetadataCacheKeyId, List<TemplateAttribute>>("metadata:data:template:attribute", MetadataCacheKeySerializer) {
        dataTemplateAttributeRepository.getByMetadataIdAndVersion(it.id, it.version ?: error("missing version"))
            .map { TemplateAttribute(dataAttribute = it) }
    }

    private val dataTemplateAttributeWorkflowCache = ServiceCache<MetadataCacheKeyId, List<DataTemplateAttributeWorkflow>>("metadata:data:template:attribute:workflow", MetadataCacheKeySerializer) {
        dataTemplateAttributeWorkflowRepository.getByMetadataIdAndVersion(it.id, it.version ?: error("missing version"), it.key ?: error("missing key"))
    }

    private suspend fun removeFromCache(id: UUID, version: Int? = null) {
        val cacheKey = MetadataCacheKeyId(id, version)
        dataTemplateCache.remove(cacheKey)
        dataTemplateAttributeCache.remove(cacheKey)
        dataTemplateAttributeWorkflowCache.remove(cacheKey, keyPrefix = true)
    }

    override suspend fun getAll() = dataTemplateRepository.getAll()

    override suspend fun getTemplate(id: UUID, version: Int) = dataTemplateCache.get(MetadataCacheKeyId(id, version))

    override suspend fun addToBatch(batch: Batch<MetadataCacheKeyId, DataTemplate>) = dataTemplateCache.addToBatch(batch)

    override suspend fun getTemplateAttributes(id: UUID, version: Int): List<TemplateAttribute> = dataTemplateAttributeCache.get(MetadataCacheKeyId(id, version)) ?: emptyList()

    override suspend fun getTemplateAttributeWorkflows(id: UUID, version: Int, key: String) =
        dataTemplateAttributeWorkflowCache.get(MetadataCacheKeyId(id, version, key)) ?: emptyList()

    override suspend fun addAttribute(
        metadataId: UUID,
        version: Int,
        attribute: TemplateAttributeInput,
        sort: Int
    ) = transaction {
        dataTemplateAttributeRepository.add(
            DataTemplateAttribute(
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
        dataTemplateAttributeRepository.deleteByMetadataIdAndVersionAndKey(metadataId, version, key)
        removeFromCache(metadataId, version)
    }

    override suspend fun setDefaultAttributes(
        metadataId: UUID,
        version: Int,
        attributes: JsonElement?
    ) = transaction {
        dataTemplateRepository.setDefaultAttributes(metadataId, version, attributes)
        removeFromCache(metadataId, version)
    }

    override suspend fun setAttributes(
        metadataId: UUID,
        version: Int,
        attributes: List<TemplateAttributeInput>
    ) = transaction {
        dataTemplateAttributeRepository.deleteByMetadataIdAndVersion(metadataId, version)
        attributes.forEachIndexed { index, attribute ->
            dataTemplateAttributeRepository.add(
                DataTemplateAttribute(
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

    override suspend fun setType(
        id: UUID,
        version: Int,
        type: DataType
    ) = transaction {
        dataTemplateRepository.setType(id, version, type)
        removeFromCache(id, version)
    }

    override suspend fun saveTemplate(id: UUID, version: Int, template: DataTemplateInput) = transaction {
        dataTemplateRepository.add(DataTemplate(
            metadataId = id,
            version = version,
            type = template.type ?: DataType.ATTRIBUTES,
            defaultAttributes = template.defaultAttributes
        ))
        dataTemplateAttributeRepository.deleteByMetadataIdAndVersion(id, version)
        template.attributes.forEachIndexed { index, attribute ->
            dataTemplateAttributeRepository.add(
                DataTemplateAttribute(
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
        removeFromCache(id, version)
    }
}