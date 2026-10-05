-- The global run-history view orders every pipeline's runs together; the existing
-- (pipeline_id, started_at desc) index can't serve a cross-pipeline ordering.
create index pipeline_run_log_started_idx
    on pipelines.pipeline_run_log (started_at desc);
