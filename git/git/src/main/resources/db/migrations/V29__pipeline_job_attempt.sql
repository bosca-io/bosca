-- Run control (GIT-SPEC-6): re-run-failed resets a run's FAILED/CANCELLED jobs to queued in place
-- and re-executes only them — succeeded jobs keep their results. `attempt` counts how many times the
-- job row has executed (1 = first run), so the UI can label a re-executed job "attempt 2" and the
-- history shows that a green job earned its status on a retry.
alter table git.pipeline_jobs
    add column attempt int not null default 1;
