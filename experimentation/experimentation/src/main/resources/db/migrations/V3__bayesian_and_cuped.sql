-- Experiments v2 — Phase 4 (Bayesian analysis) and Phase 6 (CUPED
-- variance reduction). See specs/experiments-v2/.
--
-- Two orthogonal additions in one migration because they share the
-- `experiment_results` columns and shipping them separately would
-- require two rewrites of that table in deployment order.
--
-- Phase 4 adds:
--   * `experimentation.analysis_method` Postgres enum
--     (frequentist / bayesian) and the matching column on
--     `experiments`.
--   * `experiments.bayesian_prior jsonb` for configurable per-
--     experiment Beta and Normal priors (null = sane defaults).
--   * `experiment_results.probability_beats_control double precision`
--     and `experiment_results.expected_loss double precision`.
--
-- Phase 6 adds:
--   * `conversion_goals.cuped_covariate jsonb` describing the
--     pre-period query the aggregator runs to derive per-user
--     covariate values.
--   * `experiment_results.adjusted_mean double precision` and
--     `experiment_results.adjusted_variance double precision` for
--     the CUPED-adjusted per-user statistics. The unadjusted mean
--     / variance columns stay populated so the UI can keep showing
--     "what the business metric actually looks like" alongside the
--     CUPED-narrowed CI.

create type experimentation.analysis_method as enum ('frequentist', 'bayesian');

alter table experimentation.experiments
    add column analysis_method experimentation.analysis_method not null default 'frequentist',
    add column bayesian_prior jsonb;

alter table experimentation.conversion_goals
    add column cuped_covariate jsonb;

alter table experimentation.experiment_results
    add column probability_beats_control double precision,
    add column expected_loss double precision,
    add column adjusted_mean double precision,
    add column adjusted_variance double precision;
