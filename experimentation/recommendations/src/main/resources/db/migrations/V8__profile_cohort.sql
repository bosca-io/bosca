-- "People like you" cohort key (EXP-SPEC-3, Phase 2). A profile's bounded cohort identity, derived from its
-- useAsCohort Personalization Signals' cached values (which the service restricts to CATEGORICAL / BOOLEAN
-- finite labels, so the key space stays bounded). One row per profile that has at least one enabled
-- useAsCohort signal; `cohort_key` is that profile's sorted `signal_key=value` pairs joined by '|' — e.g.
-- `age_band=25-34|gender=female`.
--
-- This view is the SINGLE source of truth for the key: the cohort co-engagement materializer (Trino, EXP-25)
-- and serving (JVM/Postgres, EXP-26) both read it, so the key they compute can never drift. It reads the
-- write-time-cached signals via the `public.profile_attribute_signals` view (bosca-core V177), so no JSONata
-- runs here.
create view profile_cohort as
select
    picked.user_id,
    string_agg(picked.signal_key || '=' || picked.cohort_value, '|' order by picked.signal_key) as cohort_key
from (
    -- Per (profile, cohort signal): the highest-priority value, canonicalized to a bare scalar label
    -- (a categorical value's JSON quotes stripped; a boolean stays true/false).
    select distinct on (pas.user_id, pas.signal_key)
        pas.user_id,
        pas.signal_key,
        case
            when left(pas.signal_value, 1) = '"' then trim(both '"' from pas.signal_value)
            else pas.signal_value
        end as cohort_value
    from public.profile_attribute_signals pas
    join personalization_signals d
      on d.key = pas.signal_key
     and d.use_as_cohort = true
     and d.enabled = true
    order by pas.user_id, pas.signal_key, pas.attribute_priority desc, pas.signal_value
) picked
group by picked.user_id;
