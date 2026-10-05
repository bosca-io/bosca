package bosca.recommendations.service

import bosca.content.recommendation.service.RecommendationContextClassifier
import bosca.db.transaction
import bosca.recommendations.jobs.RecomputeRecommendationContextsJob
import bosca.recommendations.jobs.TrainModelJob
import bosca.recommendations.jobs.ActivateContextModelJob
import bosca.recommendations.jobs.DeleteContextModelArtifactsJob
import bosca.recommendations.jobs.enqueue
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextInput
import bosca.recommendations.model.RecommendationContextModel
import bosca.recommendations.model.RecommendationTrainingStatus
import bosca.recommendations.repository.RecommendationContextRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/** PostgreSQL-backed management and lookup for saved recommendation contexts. */
@ServiceImplementation
class RecommendationContextServiceImpl(
    private val repository: RecommendationContextRepository,
    private val classifier: RecommendationContextClassifier,
) : RecommendationContextService {

    override suspend fun getAll(): List<RecommendationContext> = repository.getAll()

    override suspend fun getById(id: UUID): RecommendationContext? = repository.getById(id)

    override suspend fun getByType(type: String): RecommendationContext? = repository.getByType(normalizeType(type))

    override suspend fun add(input: RecommendationContextInput): RecommendationContext = transaction {
        repository.add(input.toContext())
    }

    override suspend fun edit(id: UUID, input: RecommendationContextInput): RecommendationContext = transaction {
        val existing = repository.lock(id) ?: throw NoSuchElementException("Recommendation context not found: $id")
        val normalizedType = normalizeType(input.type)
        if (existing.type == RecommendationContext.DEFAULT_TYPE && normalizedType != RecommendationContext.DEFAULT_TYPE) {
            throw IllegalArgumentException("The default recommendation context must keep the type 'default'")
        }
        repository.update(
            existing.copy(
                type = normalizedType,
                name = input.name.trim().ifEmpty { throw IllegalArgumentException("Recommendation context name is required") },
                description = input.description.trim(),
                contentFilter = input.contentFilter.toModel(),
                weights = input.weights.toModel(),
            ),
        )
    }

    override suspend fun delete(id: UUID) = transaction {
        val existing = repository.getById(id) ?: return@transaction
        if (existing.type == RecommendationContext.DEFAULT_TYPE) {
            throw IllegalArgumentException("The default recommendation context cannot be deleted")
        }
        repository.deleteById(id)
    }

    override suspend fun recompute() = classifier.recompute()

    override suspend fun queueRecompute() {
        RecomputeRecommendationContextsJob(trainModels = false).enqueue()
    }

    override suspend fun trainModel(contextId: UUID): RecommendationContextModel = transaction { queueModel(contextId) }

    override suspend fun trainAll() {
        for (context in repository.getAll()) trainModel(context.id)
    }

    override suspend fun getModel(version: Long): RecommendationContextModel? = repository.getModel(version)

    override suspend fun getModels(contextId: UUID): List<RecommendationContextModel> = repository.getModels(contextId)

    override suspend fun getServingModels(): List<RecommendationContextModel> = repository.getServingModels()

    override suspend fun startModel(version: Long) { repository.startModel(version) }

    override suspend fun exportModel(version: Long, personalized: Boolean) = transaction {
        val initial = requireModel(version)
        repository.lock(initial.contextId) ?: throw NoSuchElementException("Recommendation context not found")
        val model = requireModel(version)
        if (model.exported) {
            require(model.personalized == personalized) {
                "An exported model's artifact selection cannot change"
            }
            return@transaction
        }
        require(model.status == RecommendationTrainingStatus.RUNNING) { "Model is not running" }
        repository.exportModel(version, personalized)
        ActivateContextModelJob(version, model.selectionRevision).enqueue()
        Unit
    }

    override suspend fun completeModel(version: Long) = transaction {
        val initial = requireModel(version)
        repository.lock(initial.contextId) ?: return@transaction
        val model = requireModel(version)
        if (model.status == RecommendationTrainingStatus.COMPLETED) return@transaction
        require(model.exported && model.status == RecommendationTrainingStatus.RUNNING) { "Model has not been exported" }
        check(repository.completeModel(version) == 1) { "Model no longer running: $version" }
        repository.activateModel(model.contextId, version, model.selectionRevision)
        Unit
    }

    override suspend fun failModel(version: Long, failure: String) { repository.failModel(version, failure) }

    override suspend fun activateLoadedModel(version: Long, selectionRevision: Long) = transaction {
        val model = requireModel(version)
        require(model.status == RecommendationTrainingStatus.COMPLETED) { "Model is not completed" }
        repository.lock(model.contextId) ?: return@transaction
        repository.activateModel(model.contextId, version, selectionRevision)
        Unit
    }

    override suspend fun activateModel(contextId: UUID, version: Long): RecommendationContextModel = transaction {
        repository.lock(contextId) ?: throw NoSuchElementException("Recommendation context not found: $contextId")
        val model = retainedModel(contextId, version)
        require(model.status == RecommendationTrainingStatus.COMPLETED) { "Only completed models can be selected" }
        val context = repository.nextSelection(contextId)
        repository.requestModel(contextId, version, context.selectionRevision)
        ActivateContextModelJob(version, context.selectionRevision).enqueue()
        model
    }

    override suspend fun pinModel(contextId: UUID, version: Long, pinned: Boolean): RecommendationContextModel = transaction {
        repository.lock(contextId) ?: throw NoSuchElementException("Recommendation context not found: $contextId")
        val model = retainedModel(contextId, version)
        require(model.status == RecommendationTrainingStatus.COMPLETED) { "Only completed models can be pinned" }
        repository.pinModel(version, pinned)
        model.copy(pinned = pinned)
    }

    private suspend fun retainedModel(contextId: UUID, version: Long): RecommendationContextModel =
        repository.getModels(contextId).firstOrNull { it.version == version }
            ?: throw NoSuchElementException("Retained context model not found: $version")

    override suspend fun deleteModel(contextId: UUID, version: Long) = transaction {
        val context = repository.lock(contextId) ?: throw NoSuchElementException("Recommendation context not found: $contextId")
        val model = requireModel(version)
        require(model.contextId == contextId) { "Model does not belong to this context" }
        require(model.status == RecommendationTrainingStatus.COMPLETED || model.status == RecommendationTrainingStatus.FAILED) {
            "Models that are training or waiting for serving cannot be deleted"
        }
        require(context.activeModelVersion != version && context.requestedModelVersion != version) {
            "Active models and models requested for activation cannot be deleted"
        }
        require(repository.getModels(contextId).none {
            (it.status == RecommendationTrainingStatus.QUEUED || it.status == RecommendationTrainingStatus.RUNNING) &&
                it.context.activeModelVersion == version
        }) { "Model is used by an in-progress training run" }
        check(repository.deleteModel(contextId, version) == 1) { "Model no longer exists: $version" }
        DeleteContextModelArtifactsJob(contextId, version).enqueue()
        Unit
    }

    private suspend fun requireModel(version: Long): RecommendationContextModel =
        repository.getModel(version) ?: throw NoSuchElementException("Context model not found: $version")

    private suspend fun queueModel(id: UUID): RecommendationContextModel {
        repository.lock(id) ?: throw NoSuchElementException("Recommendation context not found: $id")
        val context = repository.nextSelection(id)
        val model = repository.addModel(RecommendationContextModel(
            contextId = id, revision = context.revision, selectionRevision = context.selectionRevision, context = context,
        ))
        TrainModelJob(contextModelVersion = model.version).enqueue()
        return model
    }

    private fun RecommendationContextInput.toContext() = RecommendationContext(
        type = normalizeType(type),
        name = name.trim().ifEmpty { throw IllegalArgumentException("Recommendation context name is required") },
        description = description.trim(),
        contentFilter = contentFilter.toModel(),
        weights = weights.toModel(),
    )

    private fun normalizeType(type: String): String = type.trim().lowercase().also {
        require(CONTEXT_TYPE.matches(it)) {
            "Recommendation context type must contain only letters, numbers, hyphens, or underscores"
        }
    }

    private companion object {
        val CONTEXT_TYPE = Regex("[a-z0-9][a-z0-9_-]*")
    }
}
