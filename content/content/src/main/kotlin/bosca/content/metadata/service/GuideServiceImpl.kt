package bosca.content.metadata.service

import bosca.cache.ServiceCache
import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideInput
import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.GuideStepInput
import bosca.content.metadata.model.GuideStepModule
import bosca.content.metadata.model.GuideStepModuleInput
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataCacheKeySerializer
import bosca.content.metadata.repository.GuideRepository
import bosca.content.metadata.repository.GuideStepModuleRepository
import bosca.content.metadata.repository.GuideStepRepository
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class GuideServiceImpl(
    private val guideRepository: GuideRepository,
    private val guideStepRepository: GuideStepRepository,
    private val guideStepModuleRepository: GuideStepModuleRepository,
) : GuideService {

    private val guideCache = ServiceCache(
        cacheName = "guide",
        serializer = MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val results = guideRepository.getByMetadataIds(keys.map { it.id }).associateBy { MetadataCacheKeyId(it) }
            for (key in keys) {
                batch.setData(key, results[key] ?: continue)
            }
        }
    ) {
        guideRepository.getByMetadataIdAndVersion(it.id, it.version ?: 1)
    }

    private val guideStepsCache = ServiceCache(
        cacheName = "guide:steps",
        serializer = MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val results = guideStepRepository.getByMetadataIds(keys.map { it.id }).groupBy { MetadataCacheKeyId(id = it.metadataId, version = it.version) }
            for (key in keys) {
                batch.setData(key, results[key] ?: continue)
            }
        }
    ) {
        guideStepRepository.getByMetadataIdAndVersion(it.id, it.version ?: 1)
    }

    private val guideStepModulesCache = ServiceCache(
        cacheName = "guide:step:modules",
        serializer = MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val results = guideStepModuleRepository.getByMetadataIds(keys.map { it.id }).groupBy { MetadataCacheKeyId(it) }
            for (key in keys) {
                batch.setData(key, results[key] ?: continue)
            }
        }
    ) {
        guideStepModuleRepository.getByMetadataIdAndVersionAndStep(it.id, it.version ?: 1, it.stepId ?: error("missing step id"))
    }

    private val guideStepCount = ServiceCache(
        cacheName = "guide:step:count",
        serializer = MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val results = guideStepRepository.getCountByMetadataIds(keys.map { it.id }).associateBy { MetadataCacheKeyId(it) }
            for (key in keys) {
                batch.setData(key, results[key]?.count ?: continue)
            }
        }
    ) {
        guideStepRepository.getCountByMetadataIdAndVersion(it.id, it.version ?: 1)
    }

    override suspend fun removeFromCache(id: UUID, version: Int?) {
        val id = MetadataCacheKeyId(id, version)
        guideCache.remove(id)
        guideStepCount.remove(id)
        guideStepsCache.remove(id, keyPrefix = true)
        guideStepModulesCache.remove(id, keyPrefix = true)
    }

    override suspend fun getGuide(id: UUID, version: Int) = guideCache.get(MetadataCacheKeyId(id, version))

    override suspend fun addGuidesToBatch(batch: Batch<MetadataCacheKeyId, Guide>) {
        guideCache.addToBatch(batch)
    }

    override suspend fun addStepCountsToBatch(batch: Batch<MetadataCacheKeyId, Long>) {
        guideStepCount.addToBatch(batch)
    }

    override suspend fun getGuideStep(id: UUID, version: Int, stepId: Long): GuideStep {
        val steps = guideStepsCache.get(MetadataCacheKeyId(id, version)) ?: error("missing guide steps")
        return steps.firstOrNull { it.id == stepId } ?: error("missing guide step")
    }

    override suspend fun addGuideStepsToBatch(batch: Batch<MetadataCacheKeyId, List<GuideStep>>) {
        guideStepsCache.addToBatch(batch)
        batch.ensureNotNull(emptyList())
    }

    override suspend fun getGuideStepModules(id: UUID, version: Int, stepId: Long) = guideStepModulesCache.get(MetadataCacheKeyId(id, version, stepId = stepId)) ?: emptyList()

    override suspend fun addGuideStepModulesToBatch(batch: Batch<MetadataCacheKeyId, List<GuideStepModule>>) {
        guideStepModulesCache.addToBatch(batch)
        batch.ensureNotNull(emptyList())
    }

    override suspend fun getGuideSteps(id: UUID, version: Int, offset: Int?, limit: Int?) =
        if (offset != null && limit != null) {
            guideStepRepository.getByMetadataIdAndVersion(id, version, offset, limit)
        } else if (offset != null) {
            guideStepRepository.getByMetadataIdAndVersion(id, version, offset)
        } else {
            guideStepsCache.get(MetadataCacheKeyId(id, version)) ?: emptyList()
        }

    override suspend fun setGuideRrule(id: UUID, version: Int, rrule: String) {
        guideRepository.setRrule(id, version, rrule)
        guideCache.remove(MetadataCacheKeyId(id, version))
    }

    override suspend fun setGuideType(id: UUID, version: Int, type: GuideType) {
        guideRepository.setType(id, version, type)
        guideCache.remove(MetadataCacheKeyId(id, version))
    }

    override suspend fun setGuideStepSort(id: UUID, version: Int, stepId: Long, sort: Int) {
        guideStepRepository.setGuideStepSort(id, version, stepId, sort)
        guideCache.remove(MetadataCacheKeyId(id, version), keyPrefix = true)
    }

    override suspend fun getStepCount(id: UUID, version: Int) = guideStepCount.get(MetadataCacheKeyId(id, version)) ?: 0

    override suspend fun addGuide(id: UUID, version: Int, guide: GuideInput): Guide = transaction {
        val newGuide = guideRepository.add(
            Guide(
                metadataId = id,
                version = version,
                rrule = guide.rrule,
                type = guide.guideType,
                templateMetadataId = guide.templateMetadataId,
                templateMetadataVersion = guide.templateMetadataVersion,
            )
        )
        for ((index, step) in guide.steps.withIndex()) {
            val newStep = guideStepRepository.add(
                GuideStep(
                    metadataId = id,
                    version = version,
                    stepMetadataId = step.stepMetadataId ?: error("missing step metadata id"),
                    stepMetadataVersion = step.stepMetadataVersion ?: error("missing step metadata version"),
                    sort = index
                )
            )
            for ((index, module) in step.modules.withIndex()) {
                guideStepModuleRepository.add(
                    GuideStepModule(
                        metadataId = id,
                        version = version,
                        step = newStep.id,
                        moduleMetadataId = module.moduleMetadataId ?: error("missing module metadata id"),
                        moduleMetadataVersion = module.moduleMetadataVersion
                            ?: error("missing module metadata version"),
                        sort = index
                    )
                )
            }
        }
        removeFromCache(id, version)
        newGuide
    }

    override suspend fun addGuideStep(metadataId: UUID, version: Int, step: GuideStepInput, index: Int): GuideStep = transaction {
        val newStep = guideStepRepository.add(
            GuideStep(
                metadataId = metadataId,
                version = version,
                stepMetadataId = step.stepMetadataId ?: error("missing step metadata id"),
                stepMetadataVersion = step.stepMetadataVersion ?: error("missing step metadata version"),
                sort = index
            )
        )
        for ((index, module) in step.modules.withIndex()) {
            guideStepModuleRepository.add(
                GuideStepModule(
                    metadataId = metadataId,
                    version = version,
                    step = newStep.id,
                    moduleMetadataId = module.moduleMetadataId ?: error("missing module metadata id"),
                    moduleMetadataVersion = module.moduleMetadataVersion
                        ?: error("missing module metadata version"),
                    sort = index
                )
            )
        }
        removeFromCache(metadataId, version)
        newStep
    }

    override suspend fun addGuideStepModule(metadataId: UUID, version: Int, stepId: Long, module: GuideStepModuleInput, index: Int): GuideStepModule = transaction {
        val newStep = guideStepModuleRepository.add(
            GuideStepModule(
                metadataId = metadataId,
                version = version,
                step = stepId,
                moduleMetadataId = module.moduleMetadataId ?: error("missing step metadata id"),
                moduleMetadataVersion = module.moduleMetadataVersion ?: error("missing step metadata version"),
                sort = index
            )
        )
        removeFromCache(metadataId, version)
        newStep
    }

    override suspend fun reorderSteps(metadataId: UUID, version: Int, stepIds: List<Long>) = transaction {
        val steps = guideStepRepository.getByMetadataIdAndVersion(metadataId, version)
        val stepsById = steps.associateBy { it.id }
        for ((index, stepId) in stepIds.withIndex()) {
            val step = stepsById[stepId] ?: continue
            guideStepRepository.setGuideStepSort(metadataId, version, step.id, index)
        }
        removeFromCache(metadataId, version)
    }

    override suspend fun reorderModules(metadataId: UUID, version: Int, stepId: Long, moduleIds: List<Long>) = transaction {
        val modules = guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId)
        val modulesById = modules.associateBy { it.id }
        for ((index, moduleId) in moduleIds.withIndex()) {
            val module = modulesById[moduleId] ?: continue
            guideStepModuleRepository.setSort(module.id, index)
        }
        removeFromCache(metadataId, version)
    }

    override suspend fun deleteGuide(svc: MetadataService, metadataId: UUID, version: Int) = transaction {
        val steps = guideStepRepository.getByMetadataIdAndVersion(metadataId, version)
        for (step in steps) {
            deleteGuideStep(svc, metadataId, version, step.id)
        }
        val guide = svc.getById(metadataId, version)
        guide?.id?.let {
            svc.markDeleted(it)
        }
        guideRepository.deleteByMetadataIdAndVersion(metadataId, version)
        removeFromCache(metadataId, version)
    }

    override suspend fun deleteGuideStep(svc: MetadataService, metadataId: UUID, version: Int, stepId: Long) = transaction {
        val modules = guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId)
        for (module in modules) {
            val m = svc.getById(module.moduleMetadataId ?: continue, module.moduleMetadataVersion ?: continue)
            svc.markDeleted(m?.id ?: continue)
            guideStepModuleRepository.delete(module.id)
        }
        val step = guideStepRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId)
        step.stepMetadataId?.let {
            val stepMetadata = svc.getById(it, step.stepMetadataVersion)
            stepMetadata?.id?.let {
                svc.markDeleted(it)
            }
        }
        guideStepRepository.deleteGuideStepByMetadataIdAndVersionAndStepId(metadataId, version, stepId)
        removeFromCache(metadataId, version)
    }

    override suspend fun deleteGuideStepModule(svc: MetadataService, metadataId: UUID, version: Int, stepId: Long, moduleId: Long) = transaction {
        val module = guideStepModuleRepository.getByMetadataIdAndVersionAndStepAndModule(metadataId, version, stepId, moduleId)
        val m = svc.getById(module.moduleMetadataId ?: return@transaction, module.moduleMetadataVersion ?: return@transaction)
        svc.markDeleted(m?.id ?: return@transaction)
        guideStepModuleRepository.delete(module.id)
        removeFromCache(metadataId, version)
    }
}