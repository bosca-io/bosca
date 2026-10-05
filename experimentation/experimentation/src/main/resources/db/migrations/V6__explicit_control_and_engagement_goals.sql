-- Persist the experiment baseline and extend goals with engagement outcomes.

alter type experimentation.goal_metric_type add value if not exists 'session_duration';

alter table experimentation.experiments
    add column control_variation_key varchar,
    add column analysis_revision bigint not null default 0;

-- PostgreSQL text collations do not reproduce JVM String.compareTo for every valid
-- variation key. Build a big-endian UTF-16 code-unit key so supplementary characters
-- sort exactly as they did in the legacy Kotlin `sortedBy { it.key }` path.
create function experimentation.jvm_utf16_sort_key(value text) returns bytea
language plpgsql
immutable
strict
as $$
declare
    result bytea := ''::bytea;
    code_point integer;
    supplementary integer;
begin
    for code_point in
        select ascii(piece)
        from regexp_split_to_table(value, '') as pieces(piece)
    loop
        if code_point <= 65535 then
            result := result || decode(lpad(to_hex(code_point), 4, '0'), 'hex');
        else
            supplementary := code_point - 65536;
            result := result || decode(
                lpad(to_hex(55296 + (supplementary >> 10)), 4, '0'),
                'hex'
            );
            result := result || decode(
                lpad(to_hex(56320 + (supplementary & 1023)), 4, '0'),
                'hex'
            );
        end if;
    end loop;
    return result;
end
$$;

-- Preserve the exact baseline used before this migration. A removed targeting rule
-- followed the same all-palette fallback as the default path in the legacy runtime.
-- Existing rules still intersect their rollout keys with the flag palette before
-- sorting, matching the old unresolved-weight filtering.
update experimentation.experiments e
set control_variation_key = case
    when e.targeting_rule_id is null or not exists (
        select 1
        from jsonb_array_elements(coalesce(f.targeting_rules, '[]'::jsonb)) rule
        where rule ->> 'id' = e.targeting_rule_id
    ) then (
        select variation ->> 'key'
        from jsonb_array_elements(f.variations) variation
        order by experimentation.jvm_utf16_sort_key(variation ->> 'key')
        limit 1
    )
    else (
        select variation ->> 'key'
        from jsonb_array_elements(f.variations) variation
        where exists (
            select 1
            from jsonb_array_elements(coalesce(f.targeting_rules, '[]'::jsonb)) rule
            cross join lateral jsonb_array_elements(
                coalesce(rule -> 'rollout' -> 'variationWeights', '[]'::jsonb)
            ) weight
            where rule ->> 'id' = e.targeting_rule_id
              and weight ->> 'variationKey' = variation ->> 'key'
        )
        order by experimentation.jvm_utf16_sort_key(variation ->> 'key')
        limit 1
    )
end
from experimentation.feature_flags f
where f.id = e.feature_flag_id;

do $$
begin
    if exists (
        select 1
        from experimentation.experiments
        where control_variation_key is null or btrim(control_variation_key) = ''
    ) then
        raise exception 'Cannot resolve control variation for every existing experiment';
    end if;
end
$$;

-- V5 permitted a rollout policy to name the then-implicit control as treatment.
-- Preserve that policy for operator repair, but disable active automation by moving
-- only affected RUNNING experiments to the existing PAUSED state. Draft and completed
-- rows remain in their lifecycle state and cannot enter RUNNING until validation passes.
update experimentation.experiments
set status = 'paused',
    modified = now()
where status = 'running'
  and rollout_policy is not null
  and rollout_policy ->> 'treatmentVariationKey' = control_variation_key;

drop function experimentation.jvm_utf16_sort_key(text);

alter table experimentation.experiments
    alter column control_variation_key set not null;

alter table experimentation.conversion_goals
    add column item_extra_key varchar,
    add column item_extra_value varchar;

alter table experimentation.experiment_results
    add column observation_count bigint;

update experimentation.experiment_results
set observation_count = impressions;

alter table experimentation.experiment_results
    alter column observation_count set default 0,
    alter column observation_count set not null;
