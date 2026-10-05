-- Work Ops — Phase 8.2 (specs/workops/plan.md §8.2, R14)
--
-- Automation rules: trigger / conditions / actions ride as JSONB
-- on a single row. The dispatcher pulls rules by trigger kind +
-- scope; the executor decodes the discriminated unions lazily.

create table workops.automation_rule (
    id                              uuid    not null default gen_random_uuid() primary key,
    scope                           varchar not null,
    scope_id                        uuid,
    name                            varchar not null,
    description                     varchar,
    enabled                         boolean not null default true,
    -- Single { "type": "TaskCreated", "filter": "..." } object.
    trigger                         jsonb   not null,
    -- [ { "type": "Always" }, { "type": "BqlMatch", ... }, ... ]
    conditions                      jsonb   not null default '[]'::jsonb,
    -- [ { "type": "EditTask", "fields": ... }, ... ]
    actions                         jsonb   not null default '[]'::jsonb,
    run_as_profile_id               uuid    not null,
    failure_mode                    varchar not null default 'STOP_ON_ERROR',
    execution_log_retention_days    integer not null default 30,
    max_fires_per_task_per_hour     integer not null default 5,
    version                         bigint  not null default 0
);

-- Unique within (scope, scope_id, name); use a unique index so the
-- expression `coalesce(scope_id::text, '')` can apply.
create unique index automation_rule_scope_name_idx
    on workops.automation_rule(scope, coalesce(scope_id::text, ''), name);

create index automation_rule_scope_enabled_idx
    on workops.automation_rule(scope, enabled)
    where enabled = true;

create table workops.automation_execution_log (
    id              uuid        not null default gen_random_uuid() primary key,
    rule_id         uuid        not null references workops.automation_rule(id) on delete cascade,
    task_id         uuid,
    outcome         varchar     not null,
    started_at      timestamptz not null default now(),
    finished_at     timestamptz,
    duration_ms     bigint,
    error_message   varchar
);

create index automation_execution_log_rule_idx
    on workops.automation_execution_log(rule_id, started_at desc);

create index automation_execution_log_task_idx
    on workops.automation_execution_log(task_id, started_at desc);

-- Loop guard: rolling per-rule per-task fire counter.
-- The executor decrements / resets stale rows lazily.
create table workops.automation_loop_guard (
    rule_id           uuid    not null references workops.automation_rule(id) on delete cascade,
    task_id           uuid    not null,
    fires_in_last_hour integer not null default 0,
    last_fired_at     timestamptz not null default now(),
    primary key (rule_id, task_id)
);

create index automation_loop_guard_last_fired_idx
    on workops.automation_loop_guard(last_fired_at);
