-- Seed the scheduled job that drives analytics query result cache refreshes.
-- Runs every minute; each run prunes idle cache entries and enqueues a refresh
-- job for every query whose own refresh interval has elapsed.
-- Uses a system UUID as created_by since this is an infrastructure job.

INSERT INTO scheduler.scheduled_jobs (
    name, description, job_name, job_parameters, cron_expression,
    enabled, allow_concurrent, catch_up, max_catch_up, created_by, next_run_at
) VALUES (
    'Analytics Query Cache Refresh',
    'Refreshes cached analytics query results. Enqueues a refresh job for each query whose configured refresh interval has elapsed and prunes cached parameter combinations that have gone idle.',
    'query-refresh-sweep',
    '{}',
    '*/30 * * * *',
    true,
    false,
    false,
    1,
    '00000000-0000-0000-0000-000000000000',
    NOW()
) ON CONFLICT DO NOTHING;
