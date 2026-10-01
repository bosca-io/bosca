package bosca.content.metadata.service

import bosca.attributes.TemplateAttributeInput
import bosca.cache.ServiceCache
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.attributes.model.TemplateTool
import bosca.content.collection.model.CollectionTemplate
import bosca.content.collection.model.CollectionTemplateAttribute
import bosca.content.collection.model.CollectionTemplateAttributeWorkflow
import bosca.content.collection.model.CollectionTemplateCacheKeyId
import bosca.content.collection.model.CollectionTemplateCacheKeySerializer
import bosca.content.metadata.model.CollectionTemplateFiltersInput
import bosca.content.metadata.model.CollectionTemplateInput
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataCacheKeySerializer
import bosca.content.metadata.repository.CollectionTemplateAttributeRepository
import bosca.content.metadata.repository.CollectionTemplateAttributeWorkflowRepository
import bosca.content.metadata.repository.CollectionTemplateRepository
import bosca.content.ordering.OrderingInput
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@ServiceImplementation
class CollectionTemplateServiceImpl(
    private val collectionTemplateRepository: CollectionTemplateRepository,
    private val collectionTemplateAttributeRepository: CollectionTemplateAttributeRepository,
    private val collectionTemplateAttributeWorkflowRepository: CollectionTemplateAttributeWorkflowRepository,
    private val json: Json
) : CollectionTemplateService {

    private val collectionTemplateCache = ServiceCache(
        "metadata:collection:template",
        MetadataCacheKeySerializer,
        { keys, batch ->
            val templatesByMetadataId = collectionTemplateRepository.getByMetadataIds(keys.map { it.id })
            templatesByMetadataId.forEach {
                batch.setData(MetadataCacheKeyId(it.metadataId, it.version), it)
            }
        }
    ) {
        collectionTemplateRepository.getByMetadataIdAndVersion(it.id, it.version ?: error("missing version"))
    }

    private val collectionTemplateAttributeCache = ServiceCache(
        "metadata:collection:template:attribute",
        CollectionTemplateCacheKeySerializer,
        { keys, batch ->
            val attributes = collectionTemplateAttributeRepository.getByMetadataIds(keys.map { it.id }).groupBy { CollectionTemplateCacheKeyId(it.metadataId, it.version) }
            attributes.forEach { (id, attributes) ->
                batch.setData(id, attributes)
            }
        }
    ) {
        collectionTemplateAttributeRepository.getByMetadataIdAndVersion(it.id, it.version ?: error("missing version"))
    }

    private val collectionTemplateAttributeWorkflowCache = ServiceCache(
        "metadata:collection:template:attribute:workflow",
        CollectionTemplateCacheKeySerializer,
        { keys, batch ->
            val workflows = collectionTemplateAttributeWorkflowRepository.getByMetadataIds(keys.map { it.id }).groupBy { CollectionTemplateCacheKeyId(it.metadataId, it.version) }
            keys.forEach { key ->
                batch.setData(key, workflows[key] ?: emptyList())
            }
        }
    ) {
        collectionTemplateAttributeWorkflowRepository.getByMetadataIdAndVersionAndKey(it.id, it.version ?: error("missing version"), it.key ?: error("missing key"))
    }

    private suspend fun removeFromCache(id: UUID, version: Int? = null) {
        val key = CollectionTemplateCacheKeyId(id, version)
        collectionTemplateCache.remove(MetadataCacheKeyId(id, version))
        collectionTemplateAttributeCache.remove(key)
        collectionTemplateAttributeWorkflowCache.remove(key, keyPrefix = true)
    }

    override suspend fun saveTemplate(id: UUID, version: Int, template: CollectionTemplateInput) = transaction {
        collectionTemplateRepository.add(
            metadataId = id,
            version = version,
            configuration = template.configuration,
            defaultAttributes = template.defaultAttributes,
            filters = json.encodeToJsonElement(template.filters),
            ordering = json.encodeToJsonElement(template.ordering)
        )
        setAttributes(id, version, template.attributes)
        removeFromCache(id, version)
    }

    override suspend fun getAll() = collectionTemplateRepository.getAll()

    override suspend fun getCollectionTemplate(id: UUID, version: Int) =
        collectionTemplateCache.get(MetadataCacheKeyId(id, version))

    override suspend fun addCollectionTemplatesBatch(batch: Batch<MetadataCacheKeyId, CollectionTemplate>) {
        collectionTemplateCache.addToBatch(batch)
    }

    override suspend fun getCollectionTemplateAttributes(id: UUID, version: Int) =
        collectionTemplateAttributeCache.get(CollectionTemplateCacheKeyId(id, version)) ?: emptyList()

    override suspend fun getCollectionTemplateAttributeWorkflows(id: UUID, version: Int, key: String) =
        collectionTemplateAttributeWorkflowCache.get(CollectionTemplateCacheKeyId(id, version, key)) ?: emptyList()

    override suspend fun addAttribute(
        metadataId: UUID,
        version: Int,
        attribute: TemplateAttributeInput,
        sort: Int
    ): TemplateAttribute = transaction {
        // TODO: move the other attributes
        val attribute = collectionTemplateAttributeRepository.add(
            CollectionTemplateAttribute(
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
                location = attribute.location,
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
        TemplateAttribute(collectionAttribute = attribute)
    }

    override suspend fun deleteAttribute(
        metadataId: UUID,
        version: Int,
        key: String
    ) = transaction {
        collectionTemplateAttributeRepository.deleteAttribute(metadataId, version, key)
        removeFromCache(metadataId, version)
    }

    override suspend fun setDefaultAttributes(
        metadataId: UUID,
        version: Int,
        attributes: JsonElement?
    ) {
        collectionTemplateRepository.setDefaultAttributes(metadataId, version, attributes)
        removeFromCache(metadataId, version)
    }

    override suspend fun setAttributes(
        metadataId: UUID,
        version: Int,
        attributes: List<TemplateAttributeInput>
    ) = transaction {
        collectionTemplateAttributeRepository.deleteAttributes(metadataId, version)
        attributes.forEachIndexed { index, attribute ->
            collectionTemplateAttributeRepository.add(
                CollectionTemplateAttribute(
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
                    location = attribute.location,
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
        attributes.flatMap { attribute ->
            attribute.workflows?.map {
                CollectionTemplateAttributeWorkflow(
                    metadataId = metadataId,
                    version = version,
                    key = attribute.key,
                    workflowId = it.workflowId,
                    autoRun = it.autoRun
                )
            } ?: emptyList()
        }.forEach {
            collectionTemplateAttributeWorkflowRepository.add(it)
        }
        removeFromCache(metadataId, version)
    }

    override suspend fun setConfiguration(
        metadataId: UUID,
        version: Int,
        configuration: JsonElement?
    ) {
        collectionTemplateRepository.setConfiguration(metadataId, version, configuration)
        removeFromCache(metadataId, version)
    }

    override suspend fun setFilters(
        metadataId: UUID,
        version: Int,
        filters: CollectionTemplateFiltersInput?
    ) {
        collectionTemplateRepository.setFilters(metadataId, version, json.encodeToJsonElement(filters))
        removeFromCache(metadataId, version)
    }

    override suspend fun setOrdering(
        metadataId: UUID,
        version: Int,
        ordering: List<OrderingInput>?
    ) {
        collectionTemplateRepository.setOrdering(metadataId, version, json.encodeToJsonElement(ordering))
        removeFromCache(metadataId, version)
    }
}