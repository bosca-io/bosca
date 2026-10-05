package bosca.recommendations.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.recommendations.model.RecommendationMetadataFilter
import bosca.recommendations.model.Recommendation
import bosca.recommendations.model.RecommendationCollectionSelection
import bosca.serialization.UUID

/**
 * Persists the global, strategy-keyed content recommendation candidate pool (no profile column —
 * personalization is applied at read time), with automatic expiration of stale entries.
 * Candidates are upserted per (item, strategy) tuple so that score refreshes from periodic
 * strategy evaluations replace prior values without creating duplicates.
 */
@Repository
interface RecommendationRepository {

    /**
     * Filters candidates before ranking and pagination using the selected model's resolved metadata rules.
     * Filter values must be trimmed, lowercase, and non-blank. Non-empty include lists override exclusions.
     * [metadataEnabled] is false when no completed model is selected.
     */
    @Query("""
        select recommendation.*, selected_collection.language_tag as collection_language_tag
        from recommendations.recommendations recommendation
        left join public.metadata metadata on metadata.id = recommendation.metadata_id
        left join public.collections collection on collection.id = recommendation.collection_id
        join public.language_resolution_contexts language_context
          on language_context.key = 'recommendations'
        left join lateral (
            select candidate.language_tag
            from (
                select representation.*,
                       bool_or(lower(representation.language_tag) = lower(:sourceLanguageTag)) over () as exact_exists
                from (
                    select collection.language_tag, collection.recommendable, 0 as priority
                    union all
                    select variant.language_tag, variant.recommendable, 1 as priority
                    from public.collection_language_variants variant
                    where variant.id = collection.id
                ) representation
            ) candidate
            where candidate.recommendable = true
              and (
                  (candidate.exact_exists and lower(candidate.language_tag) = lower(:sourceLanguageTag))
                  or (
                      not candidate.exact_exists
                      and (
                          exists (
                              select 1 from public.language_tag_mappings language_mapping
                              where language_mapping.context_id = language_context.id
                                and lower(language_mapping.source_language_tag) = lower(candidate.language_tag)
                                and language_mapping.resolved_language_tag = :languageTag
                          )
                          or (
                              lower(language_context.fallback_language_tag) = lower(:languageTag)
                              and lower(candidate.language_tag) = lower(language_context.fallback_language_tag)
                          )
                      )
                  )
              )
            order by candidate.priority, lower(candidate.language_tag)
            limit 1
        ) selected_collection on recommendation.collection_id is not null
        where recommendation.strategy_id = :strategyId
          and (recommendation.expires_at is null or recommendation.expires_at > now())
          and (
              (recommendation.metadata_id is not null
                  and metadata.recommendable = true
                  and (
                      exists (
                          select 1 from public.language_tag_mappings language_mapping
                          where language_mapping.context_id = language_context.id
                            and lower(language_mapping.source_language_tag) = lower(metadata.language_tag)
                            and language_mapping.resolved_language_tag = :languageTag
                      )
                      or (
                          lower(language_context.fallback_language_tag) = lower(:languageTag)
                          and lower(metadata.language_tag) = lower(language_context.fallback_language_tag)
                      )
                  )
                  and :metadataEnabled
                  and case when cardinality(:includedContentTypePrefixes) > 0
                      then exists (select 1 from unnest(:includedContentTypePrefixes) prefix
                          where starts_with(lower(btrim(split_part(coalesce(metadata.content_type, ''), ';', 1))), prefix))
                      else not exists (select 1 from unnest(:excludedContentTypePrefixes) prefix
                          where starts_with(lower(btrim(split_part(coalesce(metadata.content_type, ''), ';', 1))), prefix)) end
                  and case when cardinality(:includedAttributeTypes) > 0
                      then jsonb_typeof(metadata.attributes->'type') = 'string'
                          and lower(btrim(metadata.attributes->>'type')) = any(:includedAttributeTypes)
                      else (jsonb_typeof(metadata.attributes->'type') = 'string'
                          and lower(btrim(metadata.attributes->>'type')) = any(:excludedAttributeTypes)) is not true end)
              or (recommendation.collection_id is not null
                  and collection.recommendation_contexts @> ARRAY[CAST(:contextType AS text)]
                  and selected_collection.language_tag is not null)
          )
        order by recommendation.score desc
        limit :limit offset :offset
    """)
    suspend fun getByStrategyId(
        strategyId: UUID,
        contextType: String,
        offset: Long,
        limit: Int,
        languageTag: String = "en",
        sourceLanguageTag: String = languageTag,
        metadataEnabled: Boolean = false,
        includedContentTypePrefixes: List<String> = emptyList(),
        excludedContentTypePrefixes: List<String> = RecommendationMetadataFilter.DEFAULT_EXCLUDED_CONTENT_TYPE_PREFIXES,
        includedAttributeTypes: List<String> = emptyList(),
        excludedAttributeTypes: List<String> = emptyList(),
    ): List<Recommendation>

    /** Applies live recommendation and language eligibility to model results. */
    @Query("""
        select metadata.id
        from public.metadata metadata
        join public.language_resolution_contexts language_context
          on language_context.key = 'recommendations'
        where metadata.id = any(:metadataIds)
          and metadata.deleted = false
          and metadata.recommendable = true
          and (
              exists (
                  select 1 from public.language_tag_mappings language_mapping
                  where language_mapping.context_id = language_context.id
                    and lower(language_mapping.source_language_tag) = lower(metadata.language_tag)
                    and language_mapping.resolved_language_tag = :languageTag
              )
              or (
                  lower(language_context.fallback_language_tag) = lower(:languageTag)
                  and lower(metadata.language_tag) = lower(language_context.fallback_language_tag)
              )
          )
    """)
    suspend fun getEligibleMetadataIds(
        metadataIds: List<UUID>,
        languageTag: String,
    ): List<UUID>

    @Query("""
        select collection.id as collection_id, selected_collection.language_tag
        from public.collections collection
        join public.language_resolution_contexts language_context
          on language_context.key = 'recommendations'
        join lateral (
            select candidate.language_tag
            from (
                select representation.*,
                       bool_or(lower(representation.language_tag) = lower(:sourceLanguageTag)) over () as exact_exists
                from (
                    select collection.language_tag, collection.recommendable, 0 as priority
                    union all
                    select variant.language_tag, variant.recommendable, 1 as priority
                    from public.collection_language_variants variant
                    where variant.id = collection.id
                ) representation
            ) candidate
            where candidate.recommendable = true
              and (
                  (candidate.exact_exists and lower(candidate.language_tag) = lower(:sourceLanguageTag))
                  or (
                      not candidate.exact_exists
                      and (
                          exists (
                              select 1 from public.language_tag_mappings language_mapping
                              where language_mapping.context_id = language_context.id
                                and lower(language_mapping.source_language_tag) = lower(candidate.language_tag)
                                and language_mapping.resolved_language_tag = :languageTag
                          )
                          or (
                              lower(language_context.fallback_language_tag) = lower(:languageTag)
                              and lower(candidate.language_tag) = lower(language_context.fallback_language_tag)
                          )
                      )
                  )
              )
            order by candidate.priority, lower(candidate.language_tag)
            limit 1
        ) selected_collection on true
        where collection.id = any(:collectionIds)
          and collection.deleted = false
          and collection.recommendation_contexts @> ARRAY[CAST(:contextType AS text)]
    """)
    suspend fun getEligibleCollections(
        collectionIds: List<UUID>,
        contextType: String,
        languageTag: String,
        sourceLanguageTag: String = languageTag,
    ): List<RecommendationCollectionSelection>

    @Query("""
        insert into recommendations.recommendations (metadata_id, collection_id, strategy_id, score, reason, context, expires_at)
        values (:metadataId, :collectionId, :strategyId, :score, :reason, :context::jsonb, :expiresAt)
        on conflict (metadata_id, strategy_id) where metadata_id is not null
        do update set score = excluded.score, reason = excluded.reason, context = excluded.context::jsonb, expires_at = excluded.expires_at, created = now()
        returning *, null::text as collection_language_tag
    """)
    suspend fun upsertMetadata(recommendation: Recommendation): Recommendation

    @Query("""
        insert into recommendations.recommendations (metadata_id, collection_id, strategy_id, score, reason, context, expires_at)
        values (:metadataId, :collectionId, :strategyId, :score, :reason, :context::jsonb, :expiresAt)
        on conflict (collection_id, strategy_id) where collection_id is not null
        do update set score = excluded.score, reason = excluded.reason, context = excluded.context::jsonb, expires_at = excluded.expires_at, created = now()
        returning *, null::text as collection_language_tag
    """)
    suspend fun upsertCollection(recommendation: Recommendation): Recommendation

    @Query("delete from recommendations.recommendations where strategy_id = :strategyId")
    suspend fun deleteByStrategyId(strategyId: UUID)

    @Query(
        value = "delete from recommendations.recommendations where expires_at is not null and expires_at < now()",
        returnUpdateCount = true,
    )
    suspend fun deleteExpired(): Long
}
