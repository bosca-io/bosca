INSERT INTO scheduler.scheduled_jobs (
    name, description, job_name, job_parameters, cron_expression,
    enabled, allow_concurrent, catch_up, max_catch_up, created_by, next_run_at
) VALUES (
    'Git Repository Backup',
    'Creates git bundle backups of all active repositories and stores them in object storage.',
    'repository-backup',
    '{}',
    '0 2 * * *',
    true,
    false,
    false,
    1,
    '00000000-0000-0000-0000-000000000000',
    NOW()
) ON CONFLICT DO NOTHING;
