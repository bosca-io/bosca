-- Materialized "people like you" co-engagement edges (EXP-SPEC-3, Phase 2): for a source item and a cohort
-- (a bounded key from the viewer's useAsCohort signals, see the profile_cohort view V8), the items that
-- members of that cohort also engaged with. Mirrors recommendations.co_engagements, but keyed additionally
-- by cohort_key. A COHORT_CO_ENGAGEMENT strategy's edges are deleted wholesale before each re-evaluation,
-- then re-upserted per edge (the strategy_id FK cascades a strategy delete).
create table recommendations.cohort_co_engagements (
    source_metadata_id      uuid not null,
    cohort_key              text not null,
    co_engaged_metadata_id  uuid not null,
    strategy_id             uuid not null references recommendations.strategies(id) on delete cascade,
    score                   double precision not null default 0,
    reason                  text,
    created                 timestamptz not null default now(),
    primary key (source_metadata_id, cohort_key, co_engaged_metadata_id, strategy_id)
);

-- The serve path anchors on (source item, viewer cohort) and orders by score.
create index idx_cohort_co_engagements_lookup
    on recommendations.cohort_co_engagements (source_metadata_id, cohort_key, score desc);
create index idx_cohort_co_engagements_strategy
    on recommendations.cohort_co_engagements (strategy_id);
