-- Schedule the CI artifact-requirement sweep (GIT-SPEC-5).
--
-- Jobs whose YAML declares `requires:` are created queued but unclaimable until the registry
-- holds every required artifact. The registry's publish event releases them within seconds; this
-- sweep is the backstop for missed events and the enforcement point for requirement deadlines
-- (a still-unsatisfied job past its deadline fails loudly, naming the unmet coordinates).
--
-- Every minute — the sweep's working set is the partial index on awaiting jobs, so an idle pass
-- is one cheap indexed query. allow_concurrent false: overlap with itself is pointless.
INSERT INTO scheduler.scheduled_jobs (
    name, description, job_name, job_parameters, cron_expression,
    enabled, allow_concurrent, catch_up, max_catch_up, created_by, next_run_at
) VALUES (
    'Git CI Requirement Check',
    'Re-evaluates CI jobs gated on artifact requirements: dispatches satisfied jobs, fails jobs past their requirement deadline.',
    'pipeline-requirement-check',
    '{}',
    '* * * * *',
    true,
    false,
    false,
    1,
    '00000000-0000-0000-0000-000000000000',
    NOW()
) ON CONFLICT DO NOTHING;
