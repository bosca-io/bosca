-- Scheduled jobs for CI/CD pipeline maintenance.

INSERT INTO scheduler.scheduled_jobs (
    name, description, job_name, job_parameters, cron_expression,
    enabled, allow_concurrent, catch_up, max_catch_up, created_by, next_run_at
) VALUES (
    'CI/CD Transient Agent Cleanup',
    'Cleans up expired or orphaned transient agents, fails their assigned jobs, and triggers alerts for stuck VMs.',
    'transient-agent-cleanup',
    '{"dummy": true}',
    '*/2 * * * *',
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
    'CI/CD Pipeline Log Retention',
    'Deletes pipeline logs older than the configured retention period (default 31 days) from object storage.',
    'pipeline-log-retention',
    '{}',
    '0 5 * * *',
    true,
    false,
    false,
    1,
    '00000000-0000-0000-0000-000000000000',
    NOW()
) ON CONFLICT DO NOTHING;
