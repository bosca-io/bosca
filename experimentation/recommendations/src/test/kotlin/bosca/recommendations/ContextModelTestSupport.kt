package bosca.recommendations

import bosca.recommendations.model.RecommendationContextModel
import bosca.recommendations.repository.RecommendationContextRepositoryImpl
import bosca.serialization.UUID

/** Builds a completed, selected model fixture without contacting a trainer or TensorFlow Serving. */
suspend fun seedCompletedContextModel(
    type: String,
    personalized: Boolean = false,
): RecommendationContextModel {
    val repository = RecommendationContextRepositoryImpl()
    val saved = requireNotNull(repository.getByType(type))
    val context = repository.nextSelection(saved.id)
    val model = repository.addModel(RecommendationContextModel(
        contextId = context.id, revision = context.revision, selectionRevision = context.selectionRevision,
        context = context,
    ))
    repository.startModel(model.version)
    repository.exportModel(model.version, personalized)
    repository.completeModel(model.version)
    repository.activateModel(context.id, model.version, context.selectionRevision)
    return requireNotNull(repository.getModel(model.version))
}
