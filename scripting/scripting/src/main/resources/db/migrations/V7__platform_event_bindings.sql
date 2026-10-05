-- Phase 2: generalise trigger bindings beyond "run a script" and record run-history.

-- A binding's action is now a discriminated-union JSON document. A null action means the
-- binding still runs its legacy script_id (back-compat), so existing rows need no backfill.
alter table scripting.trigger_bindings
    add column action jsonb;

-- Non-script actions (webhook/email/slack/…) have no script, so script_id becomes optional.
alter table scripting.trigger_bindings
    alter column script_id drop not null;

-- Run-history for platform bindings, mirroring workops.automation_execution_log.
create table scripting.trigger_binding_execution_log
(
    id            uuid        not null default gen_random_uuid() primary key,
    binding_id    uuid        not null references scripting.trigger_bindings (id) on delete cascade,
    event_name    varchar     not null,
    action_type   varchar     not null,
    outcome       varchar     not null,
    started_at    timestamptz not null default now(),
    finished_at   timestamptz,
    duration_ms   bigint,
    error_message varchar
);

create index trigger_binding_execution_log_binding_idx
    on scripting.trigger_binding_execution_log (binding_id, started_at desc);
