package bosca.content.metadata.service

import bosca.attributes.TemplateAttributeInput
import bosca.cache.ServiceCache
import bosca.content.attributes.model.TemplateTool
import bosca.content.metadata.model.GuideTemplate
import bosca.content.metadata.model.GuideTemplateAttribute
import bosca.content.metadata.model.GuideTemplateInput
import bosca.content.metadata.model.GuideTemplateStep
import bosca.content.metadata.model.GuideTemplateStepModule
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataCacheKeySerializer
import bosca.content.metadata.repository.GuideTemplateAttributeRepository
import bosca.content.metadata.repository.GuideTemplateRepository
import bosca.content.metadata.repository.GuideTemplateStepModuleRepository
import bosca.content.metadata.repository.GuideTemplateStepRepository
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.serialization.JsonConverter.json
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@ServiceImplementation
class GuideTemplateServiceImpl(
    private val guideTemplateRepository: GuideTemplateRepository,
    private val guideTemplateAttributeRepository: GuideTemplateAttributeRepository,
    private val guideTemplateStepRepository: GuideTemplateStepRepository,
    private val guideTemplateStepModuleRepository: GuideTemplateStepModuleRepository,
    private val json: Json
) : GuideTemplateService {

    private val guideTemplateCache = ServiceCache(
        cacheName = "metadata:guide:template",
        serializer = MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val templates = guideTemplateRepository.getByMetadataIdBatch(keys.map { it.id }).groupBy { MetadataCacheKeyId(it) }
            keys.forEach { key ->
                templates[key]?.let { batch.setData(key, it.first()) }
            }
        }
    ) {
        guideTemplateRepository.getByMetadataIdAndVersion(it.id, it.version ?: error("missing version"))
    }

    private val guideTemplateAttributeCache = ServiceCache(
        cacheName = "metadata:guide:template:attribute",
        serializer = MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val attributes = guideTemplateAttributeRepository.getByMetadataIds(keys.map { it.id }).groupBy { MetadataCacheKeyId(it) }
            keys.forEach { key ->
                attributes[key]?.let { batch.setData(key, it) }
            }
        }
    ) {
        guideTemplateAttributeRepository.getByMetadataIdAndVersion(it.id, it.version ?: error("missing version"))
    }

    private val guideTemplateStepCache = ServiceCache(
        cacheName = "metadata:guide:template:step",
        serializer = MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val steps = guideTemplateStepRepository.getByMetadataIds(keys.map { it.id }).groupBy { MetadataCacheKeyId(id = it.metadataId, version = it.version) }
            keys.forEach { key ->
                steps[key]?.let {
                    batch.setData(key, it)
                }
            }
        }
    ) {
        guideTemplateStepRepository.getByMetadataIdAndVersion(it.id, it.version ?: error("missing version"))
    }

    private val guideTemplateStepModuleCache = ServiceCache(
        cacheName = "metadata:guide:template:module",
        serializer = MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val modules = guideTemplateStepModuleRepository.getByMetadataIds(keys.map { it.id }).groupBy { MetadataCacheKeyId(it) }
            keys.forEach { key ->
                modules[key]?.let { batch.setData(key, it) }
            }
        }
    ) {
        guideTemplateStepModuleRepository.getByMetadataIdAndVersionAndStep(it.id, it.version ?: error("missing version"), it.stepId ?: error("missing step"))
    }

    override suspend fun getAll() = guideTemplateRepository.getAll()

    override suspend fun getTemplate(id: UUID, version: Int) =
        guideTemplateCache.get(MetadataCacheKeyId(id, version))

    override suspend fun addTemplatesToBatch(batch: Batch<MetadataCacheKeyId, GuideTemplate>) {
        guideTemplateCache.addToBatch(batch)
    }

    override suspend fun getTemplateAttributes(id: UUID, version: Int) =
        guideTemplateAttributeCache.get(MetadataCacheKeyId(id, version)) ?: emptyList()

    override suspend fun addTemplateAttributesToBatch(batch: Batch<MetadataCacheKeyId, List<GuideTemplateAttribute>>) {
        guideTemplateAttributeCache.addToBatch(batch)
    }

    override suspend fun getTemplateStep(id: UUID, version: Int, stepId: Long) =
        guideTemplateStepRepository.getByMetadataIdAndVersionAndStep(id, version, stepId)

    override suspend fun getTemplateSteps(id: UUID, version: Int) =
        guideTemplateStepCache.get(MetadataCacheKeyId(id, version)) ?: emptyList()

    override suspend fun addTemplateStepsToBatch(batch: Batch<MetadataCacheKeyId, List<GuideTemplateStep>>) {
        guideTemplateStepCache.addToBatch(batch)
    }

    override suspend fun getTemplateStepModule(id: UUID, version: Int, stepId: Long, moduleId: Long) =
        guideTemplateStepModuleRepository.getByMetadataIdAndVersionAndStepModule(id, version, stepId, moduleId)

    override suspend fun getTemplateModule(id: UUID, version: Int, moduleId: Long) =
        guideTemplateStepModuleRepository.getByMetadataIdAndVersionAndModule(id, version, moduleId)

    override suspend fun getTemplateStepModules(id: UUID, version: Int, stepId: Long) =
        guideTemplateStepModuleCache.get(MetadataCacheKeyId(id, version, stepId = stepId)) ?: emptyList()

    override suspend fun addTemplateStepModulesToBatch(batch: Batch<MetadataCacheKeyId, List<GuideTemplateStepModule>>) {
        guideTemplateStepModuleCache.addToBatch(batch)
    }

    private suspend fun removeFromCache(id: UUID, version: Int? = null) {
        val id = MetadataCacheKeyId(id, version)
        guideTemplateCache.remove(id)
        guideTemplateAttributeCache.remove(id)
        guideTemplateStepCache.remove(id)
        guideTemplateStepModuleCache.remove(id, keyPrefix = true)
    }

    override suspend fun addAttribute(
        metadataId: UUID,
        version: Int,
        attribute: TemplateAttributeInput,
        sort: Int
    ) = transaction {
        // TODO: move the other attributes
        guideTemplateAttributeRepository.add(
            GuideTemplateAttribute(
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
        guideTemplateAttributeRepository.deleteByMetadataIdAndVersionAndKey(metadataId, version, key)
        removeFromCache(metadataId, version)
    }

    override suspend fun setDefaultAttributes(
        metadataId: UUID,
        version: Int,
        attributes: JsonElement?
    ) {
        guideTemplateRepository.setDefaultAttributes(metadataId, version, attributes)
        removeFromCache(metadataId, version)
    }

    override suspend fun setRrule(
        metadataId: UUID,
        version: Int,
        rrule: String?
    ) {
        guideTemplateRepository.setRrule(metadataId, version, rrule)
        removeFromCache(metadataId, version)
    }

    override suspend fun setType(
        metadataId: UUID,
        version: Int,
        type: GuideType
    ) {
        guideTemplateRepository.setType(metadataId, version, type)
        removeFromCache(metadataId, version)
    }

    override suspend fun setAttributes(
        metadataId: UUID,
        version: Int,
        attributes: List<TemplateAttributeInput>
    ) = transaction {
        guideTemplateAttributeRepository.deleteByMetadataIdAndVersion(metadataId, version)
        attributes.forEachIndexed { index, attribute ->
            guideTemplateAttributeRepository.add(
                GuideTemplateAttribute(
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
        guideTemplateRepository.setConfiguration(metadataId, version, configuration)
        removeFromCache(metadataId, version)
    }

    override suspend fun addStep(metadata: Metadata, stepMetadataId: UUID, stepMetadataVersion: Int) {
        guideTemplateStepRepository.add(
            GuideTemplateStep(
                metadataId = metadata.id,
                version = metadata.version,
                templateMetadataId = stepMetadataId,
                templateMetadataVersion = stepMetadataVersion,
                sort = 0
            )
        )
        removeFromCache(metadata.id, metadata.version)
    }

    override suspend fun addModule(metadata: Metadata, stepId: Long, moduleMetadataId: UUID, moduleMetadataVersion: Int) {
        guideTemplateStepModuleRepository.add(
            GuideTemplateStepModule(
                id = null,
                step = stepId,
                metadataId = metadata.id,
                version = metadata.version,
                templateMetadataId = moduleMetadataId,
                templateMetadataVersion = moduleMetadataVersion,
                sort = 0
            )
        )
        removeFromCache(metadata.id, metadata.version)
    }

    override suspend fun reorderSteps(metadata: Metadata, stepIds: List<Long>) = transaction {
        val steps = guideTemplateStepRepository.getByMetadataIdAndVersion(
            metadata.id,
            metadata.version
        )
        val stepsById = steps.associateBy { it.id }
        for ((index, stepId) in stepIds.withIndex()) {
            val step = stepsById[stepId] ?: continue
            guideTemplateStepRepository.setSort(step.id, index)
        }
        removeFromCache(metadata.id, metadata.version)
    }

    override suspend fun reorderModules(metadata: Metadata, stepId: Long, moduleIds: List<Long>) = transaction {
        val modules = guideTemplateStepModuleRepository.getByMetadataIdAndVersionAndStep(
            metadata.id,
            metadata.version,
            stepId
        )
        val modulesById = modules.associateBy { it.id }
        for ((index, module) in modules.withIndex()) {
            val module = modulesById[module.id] ?: continue
            guideTemplateStepModuleRepository.setSort(module.id ?: error("missing id"), index)
        }
        removeFromCache(metadata.id, metadata.version)
    }

    override suspend fun removeStep(metadata: Metadata, stepId: Long) = transaction {
        guideTemplateStepRepository.deleteByMetadataIdAndVersionAndStep(
            metadata.id,
            metadata.version,
            stepId
        )
        removeFromCache(metadata.id, metadata.version)
    }

    override suspend fun removeModule(metadata: Metadata, stepId: Long, moduleId: Long) = transaction {
        guideTemplateStepModuleRepository.deleteByMetadataIdAndVersionAndStepModule(
            metadata.id,
            metadata.version,
            stepId,
            moduleId
        )
        removeFromCache(metadata.id, metadata.version)
    }

    override suspend fun saveTemplate(id: UUID, version: Int, template: GuideTemplateInput) = transaction {
        guideTemplateRepository.add(
            id = id,
            version = version,
            defaultAttributes = template.defaultAttributes,
            configuration = template.configuration,
            rrule = template.rrule,
            type = template.type
        )
        guideTemplateStepRepository.deleteByMetadataIdAndVersion(id, version)
        template.steps.forEachIndexed { index, step ->
            val newStep = guideTemplateStepRepository.add(
                GuideTemplateStep(
                    metadataId = id,
                    version = version,
                    templateMetadataId = step.templateMetadataId,
                    templateMetadataVersion = step.templateMetadataVersion,
                    sort = index
                )
            )
            step.modules.forEachIndexed { moduleIndex, module ->
                guideTemplateStepModuleRepository.add(
                    GuideTemplateStepModule(
                        metadataId = id,
                        version = version,
                        templateMetadataId = module.templateMetadataId,
                        templateMetadataVersion = module.templateMetadataVersion,
                        step = newStep.id,
                        sort = moduleIndex,
                    )
                )
            }
        }
        removeFromCache(id, version)
    }
}