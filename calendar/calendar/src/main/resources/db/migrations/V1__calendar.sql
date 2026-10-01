-- Calendar: a calendar belongs to a Metadata record (contentType "bosca/v-calendar"),
-- the same way data, document, and guide hang off a metadata. Permissions on the
-- calendar and its events are inherited from the parent metadata via
-- MetadataPermissionEvaluator; there is no separate calendar_permissions table.
--
-- Recurrence model follows RFC 5545 (iCalendar):
--   * A "master" event (rrule is set) generates occurrences.
--   * An "override" event (original_event_id and recurrence_id are set) replaces a
--     single occurrence of its master with different details.
--   * The exdates JSONB column stores the EXDATE list — explicit occurrence
--     start times that should be skipped (used for "delete this occurrence
--     only" without creating an override row).

create table calendar.calendars (
    metadata_id   uuid not null,
    version       int not null,
    color         text not null default '#3b82f6',
    description   text not null default '',
    created       timestamptz not null default now(),
    modified      timestamptz not null default now(),
    primary key (metadata_id, version),
    foreign key (metadata_id) references public.metadata (id) on delete cascade
);

create table calendar.events (
    id                uuid primary key default gen_random_uuid(),
    metadata_id       uuid not null,
    version           int not null,
    title             text not null,
    description       text not null default '',
    location          text not null default '',
    all_day           boolean not null default false,
    starts_at         timestamptz not null,
    ends_at           timestamptz not null,
    -- Recurrence rule for master events. Stored as the bare RRULE body without
    -- the "RRULE:" prefix, e.g. "FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10".
    rrule             text,
    -- JSON array of ISO-8601 timestamps to exclude from rrule expansion.
    -- Only meaningful when rrule is non-null.
    exdates           jsonb,
    -- For overrides: the master event whose occurrence this row replaces.
    original_event_id uuid references calendar.events(id) on delete cascade,
    -- For overrides: the original (pre-override) start of the occurrence being
    -- replaced. Combined with original_event_id this is the RECURRENCE-ID.
    recurrence_id     timestamptz,
    created           timestamptz not null default now(),
    modified          timestamptz not null default now(),
    constraint ck_events_range check (ends_at >= starts_at),
    constraint ck_events_override check (
        (original_event_id is null and recurrence_id is null) or
        (original_event_id is not null and recurrence_id is not null and rrule is null)
    ),
    foreign key (metadata_id, version) references calendar.calendars (metadata_id, version) on delete cascade
);

create index ix_events_calendar on calendar.events (metadata_id, version);
create index ix_events_range on calendar.events (starts_at, ends_at);
create index ix_events_original on calendar.events (original_event_id) where original_event_id is not null;
create unique index ux_events_override on calendar.events (original_event_id, recurrence_id) where original_event_id is not null;
