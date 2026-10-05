-- Participants link profiles to calendar events with a role and RSVP status.
-- Attachments link metadata or collection items to calendar events.

create table calendar.event_participants (
    event_id    uuid not null references calendar.events(id) on delete cascade,
    profile_id  uuid not null,
    role        text not null default 'attendee',
    status      text not null default 'needs-action',
    attributes  jsonb,
    created     timestamptz not null default now(),
    primary key (event_id, profile_id)
);

create index ix_event_participants_profile on calendar.event_participants (profile_id);

create table calendar.event_attachments (
    id          uuid primary key default gen_random_uuid(),
    event_id    uuid not null references calendar.events(id) on delete cascade,
    metadata_id uuid references public.metadata(id) on delete cascade,
    collection_id uuid references public.collections(id) on delete cascade,
    relationship text not null default 'attachment',
    attributes  jsonb,
    created     timestamptz not null default now(),
    constraint ck_attachment_target check (
        (metadata_id is not null and collection_id is null) or
        (metadata_id is null and collection_id is not null)
    )
);

create index ix_event_attachments_event on calendar.event_attachments (event_id);
create index ix_event_attachments_metadata on calendar.event_attachments (metadata_id) where metadata_id is not null;
create index ix_event_attachments_collection on calendar.event_attachments (collection_id) where collection_id is not null;
