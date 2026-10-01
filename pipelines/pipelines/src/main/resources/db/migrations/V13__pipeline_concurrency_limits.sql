-- Per-pipeline concurrency & rate limits (WORKOPS-SPEC-16 / REQ-133). Caps protect downstream
-- systems from a trigger storm: at most `max_concurrent_runs` durable runs in flight at once, and
-- at most `max_runs_per_minute` started in any rolling minute. NULL on either = unlimited.
alter table pipelines.pipelines
    add column max_concurrent_runs int,
    add column max_runs_per_minute int;
