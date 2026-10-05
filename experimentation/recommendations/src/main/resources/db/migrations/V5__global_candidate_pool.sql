-- Recommendations become a candidate-generation + read-time-personalization system:
--   * strategies collapse to TRENDING, RELATED_CONTENT, ML_MODEL (segment/content/collaborative/curated
--     query strategies are superseded by the live ML model);
--   * the recommendation pool is global and strategy-keyed — personalization (the learned ranker,
--     dismissals, rating re-ranking) is applied at read time, so there is no per-profile column.

-- Remove the scheduled evaluation jobs of the strategy types we are dropping, before the strategies
-- themselves (the strategy row carries the job id). Their jobs would otherwise fire and fail forever.
-- Fully-qualified + guarded: this recommendations-schema migration runs with a search_path that need
-- not include public, and on a fresh install the scheduler's table may not exist yet — to_regclass
-- returns null and we skip, rather than aborting the whole migration.
do $$
begin
    if to_regclass('public.scheduled_jobs') is not null then
        delete from public.scheduled_jobs
        where id in (
            select scheduled_job_id
            from recommendations.strategies
            where type in ('content_based', 'collaborative', 'segment_based', 'curated')
              and scheduled_job_id is not null
        );
    end if;
end $$;

-- Drop the removed-type strategies. The on-delete-cascade FKs from recommendations, strategy_segments,
-- related_items, and placement_strategies clean up everything they produced.
delete from recommendations.strategies
where type in ('content_based', 'collaborative', 'segment_based', 'curated');

-- Segments no longer target recommendations (personalization is the ML model + behavior, not segments).
drop table if exists recommendations.strategy_segments;

-- Clear the materialized candidate pool before re-keying it. Under the old per-profile model the same
-- (item, strategy) could appear once PER profile (the ML strategy's precomputed-per-profile feeds, and
-- segment fan-out), which would collide with the new (item, strategy) unique index. This table is a
-- regenerating cache — the global pools repopulate on their evaluation schedule (TRENDING), the ML feed
-- is served live, and related edges live in related_items — so clearing it loses nothing durable.
delete from recommendations.recommendations;

-- The pool is no longer per profile. Dropping the column also drops its FK to public.profiles and every
-- index that referenced it (the per-profile score index and the (profile_id, item, strategy) uniques).
alter table recommendations.recommendations drop column profile_id;

-- Re-key uniqueness and the ordering index to (item, strategy): one row per item per strategy.
create unique index idx_recommendations_unique_metadata
    on recommendations.recommendations (metadata_id, strategy_id) where metadata_id is not null;
create unique index idx_recommendations_unique_collection
    on recommendations.recommendations (collection_id, strategy_id) where collection_id is not null;
create index idx_recommendations_strategy_score
    on recommendations.recommendations (strategy_id, score desc);
