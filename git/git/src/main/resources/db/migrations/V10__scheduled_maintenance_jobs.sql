-- Seed scheduled jobs for git repository maintenance.
-- Uses a system UUID as created_by since these are infrastructure jobs.

INSERT INTO scheduler.scheduled_jobs (
    name, description, job_name, job_parameters, cron_expression,
    enabled, allow_concurrent, catch_up, max_catch_up, created_by, next_run_at
) VALUES (
    'Git Repository Maintenance',
    'Enqueues GC jobs for all active repositories to compact packfiles and clean up orphaned packs from failed or concurrent pushes.',
    'repository-maintenance',
    '{}',
    '0 3 * * 0',
    true,
    false,
    false,
    1,
    '00000000-0000-0000-0000-000000000000',
    NOW()
) ON CONFLICT DO NOTHING;

INSERT INTO scheduler.scheduled_jobs (
    name, description, job_name, job_parameters, cron_expression,
    enabled, allow_concurrent, catch_up, max_catch_up, created_by, next_run_at
) VALUES (
    'Git Repository Purge',
    'Permanently deletes repositories that have been soft-deleted for more than 30 days, removing all pack data from object storage.',
    'repository-purge',
    '{}',
    '0 4 * * *',
    true,
    false,
    false,
    1,
    '00000000-0000-0000-0000-000000000000',
    NOW()
) ON CONFLICT DO NOTHING;
