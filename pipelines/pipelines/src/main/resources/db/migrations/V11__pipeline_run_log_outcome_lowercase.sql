-- The run-history `outcome` is now a PipelineRunStatus enum mapped via EnumMapper, which stores
-- lowercase labels (matching the native pipeline_run_status enum). Normalize pre-existing rows
-- (written as uppercase status names) so the text column is consistently lowercase. Reads already
-- uppercase-normalize, so this is for storage consistency only.
update pipelines.pipeline_run_log set outcome = lower(outcome) where outcome <> lower(outcome);
