-- Work Ops — Phase 17 (specs/workops/plan.md §17, R31)
--
-- Calendar bindings: every Work Ops row that materializes as a
-- core-calendar event records a binding row so the reverse
-- lookup ("which calendar events represent task X?") is O(1).
-- Plus auto-managed per-project / per-program / per-portfolio
-- calendars and recurring ceremonies.

-- The binding payload is intentionally lean — the calendar event
-- itself lives in core-calendar's `calendar_event` table.
create table workops.calendar_binding (
    id                  uuid    not null default gen_random_uuid() primary key,
    -- One of: project, program, portfolio, task, sprint, milestone,
    -- release, version, ceremony, sla_goal_state.
    entity_kind         varchar not null,
    entity_id           uuid    not null,
    -- core-calendar event id; FK is intentionally weak so
    -- deleting an event doesn't cascade-delete a Work Ops row.
    calendar_event_id   uuid    not null,
    -- One of CalendarEventBindingKind serialized strings.
    kind                varchar not null,
    created_at          timestamptz not null default now(),
    unique (entity_kind, entity_id, kind, calendar_event_id)
);

create index calendar_binding_entity_idx
    on workops.calendar_binding(entity_kind, entity_id);

create index calendar_binding_event_idx
    on workops.calendar_binding(calendar_event_id);

create table workops.recurring_ceremony (
    id                          uuid    not null default gen_random_uuid() primary key,
    -- One of: 'project', 'program', 'portfolio'.
    scope                       varchar not null,
    scope_id                    uuid    not null,
    name                        varchar not null,
    description                 varchar,
    -- Standard RFC 5545 RRULE.
    recurrence_rule             varchar not null,
    duration_minutes            integer not null check (duration_minutes > 0),
    participants                jsonb   not null default '[]'::jsonb,
    created_by_profile_id       uuid    not null,
    created_at                  timestamptz not null default now(),
    archived_at                 timestamptz,
    version                     bigint  not null default 0
);

create index recurring_ceremony_scope_idx
    on workops.recurring_ceremony(scope, scope_id)
    where archived_at is null;

-- Auto-managed calendar IDs on the three scope tables. core-calendar
-- owns the calendar row itself; this is just the FK.
alter table workops.portfolio
    add column if not exists calendar_id uuid;
alter table workops.program
    add column if not exists calendar_id uuid;
alter table workops.project
    add column if not exists calendar_id uuid;
