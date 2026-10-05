-- GIT-SPEC-6: persist the effective trigger parameters on the run row (dotted keys —
-- release.version, promotion.environment, inputs.*). Promotion-chain validation reads back which
-- environment earlier promotion runs targeted, the downgrade guard reads the promoted version, and
-- the dashboard's plan view renders what a run was started with.
alter table git.pipeline_runs
    add column if not exists parameters jsonb not null default '{}'::jsonb;
