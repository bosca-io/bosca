alter table pipelines.pipeline_run_rollback
    add column error     text,
    add column failed_at timestamptz;
