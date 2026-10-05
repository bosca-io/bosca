-- Persist a completed run's Output-node value on the run row (WORKOPS-SPEC-12 follow-on). Triggered
-- runs never needed it, but on-demand (manual / API) runs are now durable too, and a caller that
-- blocks briefly for a run that finishes via an async resume needs its result — which lives here.
alter table pipelines.pipeline_run add column output jsonb;
