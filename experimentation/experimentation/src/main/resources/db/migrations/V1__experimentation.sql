-- Feature Flags & Experimentation
--
-- Data model summary:
--   - A feature flag has a palette of "variations" (candidate values stored as JSON).
--   - Targeting rules pick which users get which variations via weighted rollouts.
--   - An experiment is observation+stats attached to a single targeting rule;
--     it does not store its own values — those come from the rule's rollout.
--   - Assignments and results reference variations by their stable string key,
--     not by foreign key, because variations live inside the flag JSON.

create type experimentation.flag_type as enum ('boolean', 'percentage', 'string', 'json');
create type experimentation.flag_status as enum ('draft', 'enabled', 'disabled', 'archived');
create type experimentation.experiment_status as enum ('draft', 'running', 'paused', 'completed', 'archived');
create type experimentation.goal_metric_type as enum ('unique_conversion', 'event_count');

-- Feature Flags
create table experimentation.feature_flags (
    id                      uuid primary key default gen_random_uuid(),
    key                     varchar not null unique,
    name                    varchar not null,
    description             varchar not null default '',
    type                    experimentation.flag_type not null default 'boolean',
    status                  experimentation.flag_status not null default 'draft',
    -- JSON array of Variation objects: [{key, name, description, value}, ...]
    variations              jsonb not null default '[{"key":"off","name":"Off","description":"","value":false},{"key":"on","name":"On","description":"","value":true}]'::jsonb,
    default_variation_key   varchar not null default 'off',
    -- JSON array of TargetingRule objects: [{id, conditions, rollout: {variationWeights}}, ...]
    targeting_rules         jsonb,
    -- Per-flag salt mixed into bucket-assignment hashes; regenerate to reshuffle.
    salt                    varchar not null default gen_random_uuid()::text,
    created                 timestamptz not null default now(),
    modified                timestamptz not null default now()
);
create index idx_feature_flags_status on experimentation.feature_flags (status) where status = 'enabled';

-- Mutual Exclusion Layers
create table experimentation.exclusion_layers (
    id              uuid primary key default gen_random_uuid(),
    name            varchar not null unique,
    description     varchar not null default '',
    created         timestamptz not null default now()
);

-- Experiments are observation+stats attached to a single targeting rule.
-- The targeting_rule_id is a stable rule identifier (UUID string) stored in
-- the flag's targeting_rules JSON. Null means the experiment observes the
-- flag's default-variation path (users who don't match any rule).
create table experimentation.experiments (
    id                  uuid primary key default gen_random_uuid(),
    feature_flag_id     uuid not null references experimentation.feature_flags(id) on delete cascade,
    name                varchar not null,
    description         varchar not null default '',
    hypothesis          varchar not null default '',
    status              experimentation.experiment_status not null default 'draft',
    targeting_rule_id   varchar,
    exclusion_layer_id  uuid references experimentation.exclusion_layers(id) on delete set null,
    start_date          timestamptz,
    end_date            timestamptz,
    target_sample_size  bigint,
    created             timestamptz not null default now(),
    modified            timestamptz not null default now()
);

create index idx_experiments_flag on experimentation.experiments (feature_flag_id);
create index idx_experiments_layer on experimentation.experiments (exclusion_layer_id);
-- Hot path: every flag evaluation that touches a rule with an attached
-- experiment calls findRunningExperiment(flag_id [, rule_id]). A partial
-- index keyed on the running subset keeps that lookup O(1) regardless of
-- how many archived / completed experiments accumulate over time.
create index idx_experiments_flag_running
    on experimentation.experiments (feature_flag_id, targeting_rule_id)
    where status = 'running';

-- At most one running experiment per (flag, rule) — using coalesce so the default
-- variation path (null targeting_rule_id) is treated as a single distinct slot.
create unique index idx_experiments_one_running_per_flag_rule
    on experimentation.experiments (feature_flag_id, coalesce(targeting_rule_id, ''))
    where status = 'running';

-- Variation assignments. variation_key references a Variation in the parent flag's
-- variations JSON array; there is no separate variants table.
create table experimentation.assignments (
    id              uuid primary key default gen_random_uuid(),
    experiment_id   uuid not null references experimentation.experiments(id) on delete cascade,
    variation_key   varchar not null,
    principal_id    uuid,
    installation_id varchar,
    assigned_at     timestamptz not null default now(),
    -- A row that identifies neither a principal nor an installation can never
    -- be looked up by either of the partial unique indexes below, cannot be
    -- excluded from the layer mutex, and would silently bias variation
    -- counts. Reject the all-null case at the schema level so no future
    -- write path can sneak one in.
    constraint assignments_identity_present check (principal_id is not null or installation_id is not null)
);
create unique index idx_assignments_experiment_principal on experimentation.assignments (experiment_id, principal_id) where principal_id is not null;
create unique index idx_assignments_experiment_installation on experimentation.assignments (experiment_id, installation_id) where installation_id is not null;
create index idx_assignments_principal on experimentation.assignments (principal_id);
create index idx_assignments_installation on experimentation.assignments (installation_id);
create index idx_assignments_experiment_variation on experimentation.assignments (experiment_id, variation_key);

-- Per-flag live distribution: one row per (flag, identifier) tracking which
-- variation each user is currently bucketed into. Updated on every flag
-- evaluation, so an aggregated COUNT(*) GROUP BY variation_key answers
-- "how many users are receiving this variant right now" without depending
-- on whether an experiment is attached. Independent of the assignments
-- table (which is experiment-scoped) — this exists for plain flag rollouts
-- where there's no experiment to observe the split.
--
-- The identifier column holds either a principal UUID stringified or an
-- installation id; one row per user per flag is enforced by the composite
-- primary key. When a user moves between variations (rollout reshuffled,
-- rule changed), the row is updated in place via ON CONFLICT, so the
-- per-variation counts reflect the current bucket population, not
-- everyone who ever touched the flag.
create table experimentation.flag_exposures (
    flag_id        uuid not null references experimentation.feature_flags(id) on delete cascade,
    identifier     varchar not null,
    variation_key  varchar not null,
    first_seen_at  timestamptz not null default now(),
    last_seen_at   timestamptz not null default now(),
    primary key (flag_id, identifier)
);
create index idx_flag_exposures_flag_variation on experimentation.flag_exposures (flag_id, variation_key);

-- Conversion Goals
--
-- event_type is nullable: a goal with no event_type matches events of any
-- type, useful for "did total activity per user grow?" metrics. When set,
-- the value must be the canonical capitalized form that
-- IcebergEventsToRecordTransform writes into the analytics events table
-- (`event.setField("type", type.name)`), so the experimentation aggregation
-- job can bind it directly into the Trino query without further casing.
--
-- metric_type selects between two analysis paths in the aggregation job:
--   * unique_conversion → distinct converting users, chi-squared test on a
--     binary 2x2 (control/treatment) x (converted/not) proportion table.
--   * event_count → per-user mean and variance of matching events, Welch's
--     t-test on the difference of means.
--
-- page_path optionally restricts the goal to events emitted on a specific
-- page (matched against the top-level page.path column on the events table,
-- populated by the browser SDK at emit time as window.location.pathname).
create table experimentation.conversion_goals (
    id              uuid primary key default gen_random_uuid(),
    experiment_id   uuid not null references experimentation.experiments(id) on delete cascade,
    name            varchar not null,
    event_type      varchar,
    element_type    varchar,
    element_id      varchar,
    metric_type     experimentation.goal_metric_type not null default 'unique_conversion',
    page_path       varchar,
    created         timestamptz not null default now(),
    -- The set of allowed event_type values is locked in lock-step with the
    -- Kotlin enum `bosca.analytics.model.EventType` (analytics-models module).
    -- Adding a new variant to that enum REQUIRES a follow-up migration that
    -- drops and re-creates this constraint, otherwise inserting goals against
    -- the new value will silently fail at the database with a check violation
    -- and the GraphQL mutation will surface a confusing 5xx instead of an
    -- understandable validation error. ConversionGoalEventTypeSerializationTest
    -- exists specifically to lock the wire form down so this enum, the Iceberg
    -- `type` column, and this check constraint stay byte-identical.
    constraint conversion_goals_event_type_canonical
        check (event_type is null or event_type in
            ('Session','Interaction','Impression','Completion','Installation','Error'))
);
create index idx_conversion_goals_experiment on experimentation.conversion_goals (experiment_id);

-- Aggregated metrics per (experiment, variation, goal). variation_key is a
-- stable string reference into the parent flag's variations JSON array.
create table experimentation.experiment_results (
    id                      uuid primary key default gen_random_uuid(),
    experiment_id           uuid not null references experimentation.experiments(id) on delete cascade,
    variation_key           varchar not null,
    goal_id                 uuid not null references experimentation.conversion_goals(id) on delete cascade,
    impressions             bigint not null default 0,
    conversions             bigint not null default 0,
    conversion_rate         double precision not null default 0.0,
    confidence_level        double precision,
    lift_over_control       double precision,
    -- Per-variant per-goal mean and variance for event_count goals; null for
    -- unique_conversion goals (which only need conversion_rate). Variance is
    -- the unbiased sample variance with Bessel's correction (n-1 denominator).
    mean                    double precision,
    variance                double precision,
    updated_at              timestamptz not null default now(),
    unique (experiment_id, variation_key, goal_id)
);
create index idx_experiment_results_experiment on experimentation.experiment_results (experiment_id);

-- Analysis Reports.
--
-- `summary` and `recommendation` are ALWAYS the deterministic prose
-- produced by `ExperimentAnalysis.renderVerdict` — they are the
-- authoritative product surface for the verdict. `details` is the
-- structured per-goal/per-variation JSON the admin UI uses to render
-- tables and badges.
--
-- `ai_insights` is an OPTIONAL structured JSON blob written by the
-- LLM analyzer (hypothesis reconciliation, cross-goal patterns,
-- follow-up experiment ideas, SRM root-cause hints). It is strictly
-- additive — the executor never replaces summary/recommendation with
-- AI output, and the analyzer rejects any LLM response that does not
-- echo the deterministic verdict back via a `verdictAcknowledged`
-- field, so the AI section can never silently contradict the
-- deterministic decision. Null when no AI analyzer is wired or when
-- the verifier rejected the response.
create table experimentation.analysis_reports (
    id              uuid primary key default gen_random_uuid(),
    experiment_id   uuid not null references experimentation.experiments(id) on delete cascade,
    summary         varchar not null,
    recommendation  varchar not null,
    details         jsonb not null,
    confidence      double precision,
    ai_insights     jsonb,
    created         timestamptz not null default now()
);
create index idx_analysis_reports_experiment on experimentation.analysis_reports (experiment_id);
