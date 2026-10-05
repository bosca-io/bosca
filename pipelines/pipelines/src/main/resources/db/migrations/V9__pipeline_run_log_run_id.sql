-- Link a finished-run history row to the durable run it came from (WORKOPS-SPEC-13 / REQ-123), so
-- Studio can open a finished run's per-node timeline from run history. Nullable: inline manual/API
-- runs have no durable run row, and a run row purged by retention sets this back to null (the history
-- row is kept).
alter table pipelines.pipeline_run_log
    add column run_id uuid references pipelines.pipeline_run (id) on delete set null;
