package bosca.recommendations.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.recommendations.model.RecommendationMetadataFilter
import bosca.recommendations.model.CoEngagement
import bosca.serialization.UUID

/**
 * Persists precomputed behavioral co-engagement edges (source content -> co-engaged content) produced
 * by [bosca.recommendations.model.RecommendationStrategyType.CO_ENGAGEMENT] strategies. Reads are
 * anchored on a source item and ordered by score for the "people who engaged with this also engaged
 * with…" serve path; a strategy's edges are deleted wholesale before each re-evaluation, then
 * re-upserted per edge.
 */
@Repository
interface CoEngagementRepository {

    /**
     * Filters candidates before ranking and pagination using the selected model's resolved metadata rules.
     * Filter values must be trimmed, lowercase, and non-blank. Non-empty include lists override exclusions.
     * [metadataEnabled] is false when no completed model is selected.
     */
    @Query("""
        select deduped.* from (
            select distinct on (co_engagement.co_engaged_metadata_id) co_engagement.*
            from recommendations.co_engagements co_engagement
            join public.metadata metadata on metadata.id = co_engagement.co_engaged_metadata_id
            join public.language_resolution_contexts language_context
              on language_context.key = 'recommendations'
            where co_engagement.source_metadata_id = :sourceMetadataId
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
                      and lower(btrim(metadata.attributes->>'type')) = any(:excludedAttributeTypes)) is not true end
            order by co_engagement.co_engaged_metadata_id, co_engagement.score desc
        ) deduped
        order by deduped.score desc, deduped.co_engaged_metadata_id
        offset :offset limit :limit
    """)
    suspend fun getBySource(
        sourceMetadataId: UUID,
        metadataEnabled: Boolean,
        limit: Int,
        languageTag: String = "en",
        includedContentTypePrefixes: List<String> = emptyList(),
        excludedContentTypePrefixes: List<String> = RecommendationMetadataFilter.DEFAULT_EXCLUDED_CONTENT_TYPE_PREFIXES,
        includedAttributeTypes: List<String> = emptyList(),
        excludedAttributeTypes: List<String> = emptyList(),
        offset: Long = 0,
    ): List<CoEngagement>

    @Query("""
        insert into recommendations.co_engagements (source_metadata_id, co_engaged_metadata_id, strategy_id, score, reason)
        values (:sourceMetadataId, :coEngagedMetadataId, :strategyId, :score, :reason)
        on conflict (source_metadata_id, co_engaged_metadata_id, strategy_id)
        do update set score = excluded.score, reason = excluded.reason, created = now()
        returning *
    """)
    suspend fun upsert(item: CoEngagement): CoEngagement

    @Query("delete from recommendations.co_engagements where strategy_id = :strategyId")
    suspend fun deleteByStrategyId(strategyId: UUID)
}
