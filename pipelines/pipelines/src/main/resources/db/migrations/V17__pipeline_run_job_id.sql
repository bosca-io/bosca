-- WORKOPS-SPEC-7 "run = a job": link a durable run to the platform job that drives it. The run's
-- backing-work jobs and resume jobs attach to this job as children, so it is not fully complete until
-- the run reaches a terminal state, and its completion is the single run-completion hook. Nullable:
-- an inline/on-demand run has no driving job.
alter table pipelines.pipeline_run
    add column run_job_id uuid;
