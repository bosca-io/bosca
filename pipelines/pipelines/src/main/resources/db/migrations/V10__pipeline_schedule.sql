-- Cron schedule for time-triggered pipeline runs (WORKOPS-SPEC-15 / REQ-128). Null = not scheduled.
-- The schedule is mirrored into a SchedulerService ScheduledJob on save; this column is the source of
-- truth that survives in the pipeline row (and rides the git YAML like the other endpoint settings).
alter table pipelines.pipelines
    add column schedule text;
