-- Work Ops — Phase 8.3 (specs/workops/plan.md §8.3, R15)
--
-- Worklogs and the project-level estimate adjustment mode. The
-- task table already carries `time_spent_seconds` and
-- `remaining_estimate_seconds` (V2); this migration adds the
-- worklog table and the project mode column.

create table workops.worklog (
    id                  uuid    not null default gen_random_uuid() primary key,
    task_id             uuid    not null references workops.task(id) on delete cascade,
    profile_id          uuid    not null,
    time_spent_seconds  bigint  not null check (time_spent_seconds > 0),
    started_at          timestamptz not null,
    comment             varchar,
    worklog_visibility  varchar not null default 'ALL',
    created_at          timestamptz not null default now(),
    modified_at         timestamptz not null default now(),
    deleted_at          timestamptz
);

create index worklog_task_idx on workops.worklog(task_id) where deleted_at is null;
create index worklog_profile_idx on workops.worklog(profile_id, started_at desc);

alter table workops.project
    add column if not exists worklog_estimate_adjustment_mode varchar not null default 'AUTO_REDUCE';

-- Epic rollup columns. Phase 2 defined epicTaskId pointers; this
-- adds the tallies the rollup recomputer maintains.
alter table workops.task
    add column if not exists epic_total_estimate_seconds  bigint  not null default 0;
alter table workops.task
    add column if not exists epic_total_remaining_seconds bigint  not null default 0;
alter table workops.task
    add column if not exists epic_total_spent_seconds     bigint  not null default 0;
alter table workops.task
    add column if not exists epic_child_count             integer not null default 0;
alter table workops.task
    add column if not exists epic_child_done_count        integer not null default 0;
