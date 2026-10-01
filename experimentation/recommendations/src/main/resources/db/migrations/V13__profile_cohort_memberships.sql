-- A profile can belong to every cohort value produced by its enabled useAsCohort signals. The original
-- profile_cohort view collapsed repeated values for the same signal key to one arbitrary winner, which lost
-- intentionally multi-valued attributes such as learned interests. Keep the existing view name for Trino and
-- serving compatibility, but expose one deterministic row per distinct (profile, signal key, signal value).
--
-- A profile with age_band=25-34 and learned interests in hiking and photography therefore has three cohort
-- memberships rather than one composite identity. Each opaque cohort_key is the canonical JSON tuple
-- [signal key, semantic value], which is injective even when either component contains '=' or other separators.
--
-- Cohort materialization defensively selects at most 64 distinct values per profile/key and excludes semantic
-- values longer than 256 characters before Trino's co-engagement self-join. Signals remain independently
-- derived and cached; the materializer groups each selected membership independently and serving merges
-- candidates across all memberships.
create or replace view profile_cohort as
with normalized as (
    select
        pas.user_id,
        pas.signal_key,
        case
            when jsonb_typeof(pas.signal_value::jsonb) = 'string' then pas.signal_value::jsonb #>> '{}'
            else pas.signal_value::jsonb::text
        end as cohort_value,
        pas.attribute_priority
    from public.profile_attribute_signals pas
    join personalization_signals d
      on d.key = pas.signal_key
     and d.use_as_cohort = true
     and d.enabled = true
), distinct_values as (
    select user_id, signal_key, cohort_value, max(attribute_priority) as attribute_priority
    from normalized
    where char_length(cohort_value) <= 256
    group by user_id, signal_key, cohort_value
), ranked as (
    select
        user_id,
        signal_key,
        cohort_value,
        row_number() over (
            partition by user_id, signal_key
            order by attribute_priority desc, cohort_value
        ) as value_rank
    from distinct_values
)
select
    user_id,
    jsonb_build_array(signal_key, cohort_value)::text as cohort_key
from ranked
where value_rank <= 64;
