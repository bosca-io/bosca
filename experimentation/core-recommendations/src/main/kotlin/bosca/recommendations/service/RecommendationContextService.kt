package bosca.recommendations.service

import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextInput
import bosca.recommendations.model.RecommendationContextModel
import bosca.serialization.UUID
import bosca.service.Service

/** Manages saved recommendation contexts and the cached content classification derived from them. */
interface RecommendationContextService : Service {

    /** Returns every saved context ordered by name. */
    suspend fun getAll(): List<RecommendationContext>

    /** Returns a context by identifier, or null when it does not exist. */
    suspend fun getById(id: UUID): RecommendationContext?

    /** Returns the context selected by the supplied type value, or null when it does not exist. */
    suspend fun getByType(type: String): RecommendationContext?

    /** Creates a saved context without queuing content reclassification or model training. */
    suspend fun add(input: RecommendationContextInput): RecommendationContext

    /** Saves context settings without queuing reclassification, training, or changing model selection. */
    suspend fun edit(id: UUID, input: RecommendationContextInput): RecommendationContext

    /** Deletes a context without queuing reclassification. The `default` context cannot be deleted. */
    suspend fun delete(id: UUID)

    /** Reclassifies every metadata item and collection from the current saved context definitions. */
    suspend fun recompute()

    /** Queues content reclassification from all saved contexts without starting model training. */
    suspend fun queueRecompute()

    /** Captures the context's saved settings and queues a durable training job for that snapshot. */
    suspend fun trainModel(contextId: UUID): RecommendationContextModel

    /** Queues a snapshot of the current settings for every saved context. */
    suspend fun trainAll()

    /** Returns a specific immutable training snapshot. */
    suspend fun getModel(version: Long): RecommendationContextModel?

    /** Returns history plus active and pinned versions for a context. */
    suspend fun getModels(contextId: UUID): List<RecommendationContextModel>

    /** Returns exports required by serving and pending load validation. */
    suspend fun getServingModels(): List<RecommendationContextModel>

    /** Records that the durable job has started. */
    suspend fun startModel(version: Long)

    /** Records completed exports; eligible item lookups remain inside the artifacts. */
    suspend fun exportModel(version: Long, personalized: Boolean)

    /** Completes load validation and activates only if its selection intent remains current. */
    suspend fun completeModel(version: Long)

    /** Activates a load-validated retained version only for the current selection intent. */
    suspend fun activateLoadedModel(version: Long, selectionRevision: Long)

    /** Records a terminal failure without changing the active version. */
    suspend fun failModel(version: Long, failure: String)

    /** Selects a retained model as a new administrator intent, superseding older jobs. */
    suspend fun activateModel(contextId: UUID, version: Long): RecommendationContextModel

    /** Pins or unpins a retained model. */
    suspend fun pinModel(contextId: UUID, version: Long, pinned: Boolean): RecommendationContextModel

    /** Deletes terminal model history and queues artifact cleanup; active and requested models cannot be deleted. */
    suspend fun deleteModel(contextId: UUID, version: Long)
}
