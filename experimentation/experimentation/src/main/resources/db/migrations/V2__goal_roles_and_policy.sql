-- Experiments v2 — Phase 1 (guardrail roles) and Phase 2 (rollout policy).
--
-- See specs/experiments-v2/ for context. This migration adds three
-- things:
--
--   1. A `conversion_goal_role` enum and a `role` column on
--      `conversion_goals`, so the analyzer can distinguish primary
--      metrics (drive SHIP/DO_NOT_SHIP), secondary metrics (inform but
--      don't halt), and guardrails (halt on regression).
--
--   2. A `rollout_policy` JSONB column on `experiments`, carrying a
--      small typed record describing how the rollout controller should
--      react to analysis results (manual, scheduled steps, adaptive
--      steps, adaptive continuous). JSONB not a normalized table
--      because the policy is small, read-once-per-analysis, and
--      evolves faster than the rest of the schema. See
--      specs/experiments-v2/requirements.md D2.
--
--   3. A `rollout_policy_events` audit table — append-only,
--      time-indexed, with old/new rollout snapshots. This is what
--      operators read to answer "why did my flag change at 2am".
--
-- Existing rows are backfilled to PRIMARY role to preserve current
-- verdict semantics (all goals today behave as primaries). Existing
-- experiments have no rollout policy, so the controller job is a
-- no-op for them.

create type experimentation.conversion_goal_role as enum ('primary', 'secondary', 'guardrail');

-- Action recorded on every rollout controller decision. Declared here
-- (not inline on the events table) so Kotlin's RolloutPolicyAction enum
-- round-trips through the default EnumMapper via lowercase labels, matching
-- the GoalMetricType / ConversionGoalRole conventions elsewhere in
-- this schema. Adding a new variant requires a follow-up migration
-- (ALTER TYPE ... ADD VALUE).
create type experimentation.rollout_policy_action as enum ('advanced', 'held', 'halted', 'completed');

alter table experimentation.conversion_goals
    add column role experimentation.conversion_goal_role not null default 'primary';

alter table experimentation.experiments
    add column rollout_policy jsonb;

-- Audit trail for the rollout controller. One row per controller action
-- (advance, hold, halt, complete). Old/new weights are captured as the
-- exact JSON the controller read and wrote so replaying a decision is
-- possible from the event alone without needing the flag's historical
-- state.
create table experimentation.rollout_policy_events (
    id              uuid primary key default gen_random_uuid(),
    experiment_id   uuid not null references experimentation.experiments(id) on delete cascade,
    -- Controller action — see the experimentation.rollout_policy_action enum.
    action          experimentation.rollout_policy_action not null,
    -- Human-readable reason string, e.g. "verdict=SHIP advanced step 1→2"
    -- or "verdict=HALT guardrail 'page-load' regressed -2.3%".
    reason          varchar not null,
    -- Variation-key → weight snapshot BEFORE the controller's edit. Null
    -- when the action is 'held' without a write.
    old_weights     jsonb not null,
    -- Variation-key → weight snapshot AFTER the controller's edit.
    -- Equal to old_weights when the action is 'held'.
    new_weights     jsonb not null,
    created         timestamptz not null default now()
);
create index idx_rollout_policy_events_experiment
    on experimentation.rollout_policy_events (experiment_id, created desc);
