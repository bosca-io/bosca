-- Work Ops — Phase 8.1 (specs/workops/plan.md §8.1, R13)
--
-- Service Level Agreement plumbing: working calendars,
-- policies + goals, and the per-task tracker the tick job
-- mutates. Calendars hold weekly hours and holidays as jsonb so
-- new days / holidays don't require a schema change.

create table workops.working_calendar (
    id            uuid    not null default gen_random_uuid() primary key,
    name          varchar not null unique,
    description   varchar,
    time_zone     varchar not null default 'UTC',
    -- { "MONDAY": [{"startLocal":"09:00","endLocal":"17:00"}], ... }
    weekly_hours  jsonb   not null default '{}'::jsonb,
    -- ["2026-01-01","2026-12-25", ...]
    holidays      jsonb   not null default '[]'::jsonb,
    version       bigint  not null default 0
);

-- 24x7 calendar — the fallback when no WorkingCalendar is
-- configured. The dispatcher / tick job treats `weekly_hours = {}`
-- as "every minute counts".
insert into workops.working_calendar (id, name, description, time_zone, weekly_hours, holidays) values (
    'c0000000-0000-0000-0000-000000000001',
    'Always',
    '24x7 calendar — every minute is working time. Fallback when no calendar is configured.',
    'UTC',
    $$ {
        "MONDAY":    [{"startLocal":"00:00","endLocal":"24:00"}],
        "TUESDAY":   [{"startLocal":"00:00","endLocal":"24:00"}],
        "WEDNESDAY": [{"startLocal":"00:00","endLocal":"24:00"}],
        "THURSDAY":  [{"startLocal":"00:00","endLocal":"24:00"}],
        "FRIDAY":    [{"startLocal":"00:00","endLocal":"24:00"}],
        "SATURDAY":  [{"startLocal":"00:00","endLocal":"24:00"}],
        "SUNDAY":    [{"startLocal":"00:00","endLocal":"24:00"}]
    } $$::jsonb,
    '[]'::jsonb
);

-- Mon–Fri 09:00–17:00 UTC sample for documentation / first-touch.
insert into workops.working_calendar (id, name, description, time_zone, weekly_hours, holidays) values (
    'c0000000-0000-0000-0000-000000000002',
    'Mon-Fri 09-17 UTC',
    'Standard business-hours sample.',
    'UTC',
    $$ {
        "MONDAY":    [{"startLocal":"09:00","endLocal":"17:00"}],
        "TUESDAY":   [{"startLocal":"09:00","endLocal":"17:00"}],
        "WEDNESDAY": [{"startLocal":"09:00","endLocal":"17:00"}],
        "THURSDAY":  [{"startLocal":"09:00","endLocal":"17:00"}],
        "FRIDAY":    [{"startLocal":"09:00","endLocal":"17:00"}]
    } $$::jsonb,
    '[]'::jsonb
);

create table workops.sla_policy (
    id          uuid    not null default gen_random_uuid() primary key,
    name        varchar not null unique,
    description varchar,
    version     bigint  not null default 0
);

create table workops.sla_goal (
    id                  uuid    not null default gen_random_uuid() primary key,
    policy_id           uuid    not null references workops.sla_policy(id) on delete cascade,
    name                varchar not null,
    -- BQL expression evaluated against the task; the policy
    -- evaluator decides start / pause / resume / stop edges.
    start_conditions    varchar not null,
    pause_conditions    varchar,
    stop_conditions     varchar not null,
    target_minutes      integer not null,
    at_risk_at_percent  integer not null default 80,
    calendar_id         uuid    references workops.working_calendar(id),
    display_order       integer not null default 0,
    unique (policy_id, name)
);

create index sla_goal_policy_idx on workops.sla_goal(policy_id);

create table workops.task_sla_state (
    task_id              uuid       not null references workops.task(id) on delete cascade,
    goal_id              uuid       not null references workops.sla_goal(id) on delete cascade,
    started_at           timestamptz not null,
    paused_at            timestamptz,
    paused_total_seconds bigint     not null default 0,
    due_at               timestamptz not null,
    outcome              varchar    not null default 'OPEN',
    at_risk_emitted      boolean    not null default false,
    breach_emitted       boolean    not null default false,
    version              bigint     not null default 0,
    primary key (task_id, goal_id)
);

-- The tick job pulls `OPEN` rows whose next boundary
-- (at-risk or breach) falls inside the tick window.
create index task_sla_state_open_due_idx
    on workops.task_sla_state(due_at)
    where outcome = 'OPEN';

create index task_sla_state_task_idx
    on workops.task_sla_state(task_id);

-- Project rows reference the active policy via default_sla_policy_id.
-- The column is nullable — projects without an SLA configured are
-- valid; the tick job simply has no rows to process.
alter table workops.project
    add column if not exists default_sla_policy_id uuid;

alter table workops.project
    add constraint project_default_sla_policy_fk
    foreign key (default_sla_policy_id)
    references workops.sla_policy(id) on delete restrict;
