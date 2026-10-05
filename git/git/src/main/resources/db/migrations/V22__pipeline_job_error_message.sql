-- Failure summary for job-level failures that happen outside any step:
-- disk-space checks, secret decryption, orphaned agents, timeouts. Set
-- by the agent (via updateJobStatus) or server-side reapers so the UI
-- can show why a job failed when no step carries the explanation.
alter table git.pipeline_jobs
    add column error_message varchar;
