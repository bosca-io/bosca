-- Schedule the DFS pack reaper.
--
-- GC and thin-pack compaction soft-delete replaced packs (set `deleted_at`)
-- rather than removing their object-storage files inline. This job physically
-- deletes those files once each pack's grace window has elapsed. The grace
-- window (enforced in the reap query, not here) keeps deleted packs available
-- long enough for any in-flight clone/fetch to finish reading them.
--
-- Runs hourly; allow_concurrent is false so a slow run never overlaps itself.
INSERT INTO scheduler.scheduled_jobs (
    name, description, job_name, job_parameters, cron_expression,
    enabled, allow_concurrent, catch_up, max_catch_up, created_by, next_run_at
) VALUES (
    'Git DFS Pack Reaper',
    'Physically deletes object-storage files for GC/compaction-replaced packs after their grace window elapses.',
    'dfs-pack-reap',
    '{}',
    '0 * * * *',
    true,
    false,
    false,
    1,
    '00000000-0000-0000-0000-000000000000',
    NOW()
) ON CONFLICT DO NOTHING;
