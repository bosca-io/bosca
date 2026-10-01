package bosca.recommendations.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextModel
import bosca.serialization.UUID

/** Persists named recommendation contexts and their typed JSON content filters. */
@Repository
interface RecommendationContextRepository {

    @Query("select * from recommendations.contexts order by name")
    suspend fun getAll(): List<RecommendationContext>

    @Query("select * from recommendations.contexts where id = :id")
    suspend fun getById(id: UUID): RecommendationContext?

    @Query("select * from recommendations.contexts where type = :type")
    suspend fun getByType(type: String): RecommendationContext?

    @Query("""
        insert into recommendations.contexts (type, name, description, content_filter, weights)
        values (:type, :name, :description, :contentFilter::jsonb, :weights::jsonb)
        returning *
    """)
    suspend fun add(context: RecommendationContext): RecommendationContext

    @Query("""
        update recommendations.contexts
        set type = :type, name = :name, description = :description,
            content_filter = :contentFilter::jsonb, weights = :weights::jsonb,
            revision = revision + 1, modified = now()
        where id = :id
        returning *
    """)
    suspend fun update(context: RecommendationContext): RecommendationContext

    @Query("delete from recommendations.contexts where id = :id")
    suspend fun deleteById(id: UUID)

    /** Serializes saves and administrator selections for one context. */
    @Query("select * from recommendations.contexts where id = :id for update")
    suspend fun lock(id: UUID): RecommendationContext?

    /** Records a new intent; model versions and selection revisions are independent counters. */
    @Query("""
        update recommendations.contexts set selection_revision = selection_revision + 1, requested_model_version = null
        where id = :id returning *
    """)
    suspend fun nextSelection(id: UUID): RecommendationContext

    /** Captures immutable settings for a queued training job. */
    @Query("""
        insert into recommendations.context_models (context_id, revision, selection_revision, context)
        values (:contextId, :revision, :selectionRevision, :context::jsonb) returning *
    """)
    suspend fun addModel(model: RecommendationContextModel): RecommendationContextModel

    /** Returns a captured training version. */
    @Query("select * from recommendations.context_models where version = :version")
    suspend fun getModel(version: Long): RecommendationContextModel?

    /** Lists recent history plus protected active/pinned models. */
    @Query("""
        select m.* from recommendations.context_models m
        join recommendations.contexts c on c.id = m.context_id
        where m.context_id = :contextId
          and (m.pinned or m.version = c.active_model_version or m.status <> 'completed'
               or m.version in (
                   select version from recommendations.context_models
                   where context_id = :contextId and status = 'completed'
                   and version is distinct from c.active_model_version
                   order by version desc limit 5
               ))
        order by m.version desc
    """)
    suspend fun getModels(contextId: UUID): List<RecommendationContextModel>

    /** Versions needed by the loader, including exports awaiting validation. */
    @Query("""
        select m.* from recommendations.context_models m
        join recommendations.contexts c on c.id = m.context_id
        where m.exported and (m.version = c.active_model_version or m.version = c.requested_model_version
                             or m.status = 'running')
        order by m.version
    """)
    suspend fun getServingModels(): List<RecommendationContextModel>

    /** Moves a queued version into training, idempotently across redelivery. */
    @Query("""
        update recommendations.context_models set status = 'running', started = coalesce(started, now())
        where version = :version and status = 'queued'
    """, returnUpdateCount = true)
    suspend fun startModel(version: Long): Int

    /** Records uploaded exports; completion waits until both required exports can serve. */
    @Query("""
        update recommendations.context_models set exported = true, personalized = :personalized
        where version = :version and status = 'running'
    """, returnUpdateCount = true)
    suspend fun exportModel(version: Long, personalized: Boolean): Int

    /** Returns one only when a running export completed; zero means another terminal transition won. */
    @Query("""
        update recommendations.context_models set status = 'completed', completed = now(), failure = null
        where version = :version and status = 'running'
    """, returnUpdateCount = true)
    suspend fun completeModel(version: Long): Int

    /** Makes failure observable without replacing the active version. */
    @Query("""
        update recommendations.context_models
        set status = case when status = 'completed' then status else 'failed'::recommendations.training_status end,
            completed = coalesce(completed, now()), failure = :failure
        where version = :version
    """)
    suspend fun failModel(version: Long, failure: String)

    /** Selects only the model whose activation intent is still current. */
    @Query("""
        update recommendations.contexts set active_model_version = :version, requested_model_version = null
        where id = :id and selection_revision = :selectionRevision
    """, returnUpdateCount = true)
    suspend fun activateModel(id: UUID, version: Long, selectionRevision: Long): Int

    /** Requests a retained version while the active version remains usable during loading. */
    @Query("""
        update recommendations.contexts set requested_model_version = :version
        where id = :id and selection_revision = :selectionRevision
    """)
    suspend fun requestModel(id: UUID, version: Long, selectionRevision: Long)

    /** Protects a retained model independently of its position in history. */
    @Query("update recommendations.context_models set pinned = :pinned where version = :version")
    suspend fun pinModel(version: Long, pinned: Boolean)

    /** Deletes a terminal generation after the service locks and verifies its context selection. */
    @Query("delete from recommendations.context_models where context_id = :contextId and version = :version", returnUpdateCount = true)
    suspend fun deleteModel(contextId: UUID, version: Long): Int
}
