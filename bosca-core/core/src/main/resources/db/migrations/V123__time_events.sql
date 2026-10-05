-- Time Event Type Registry
create table time_event_types (
    id              varchar not null,
    name            varchar not null,
    description     varchar not null,
    schema          jsonb,
    configuration   jsonb not null default '{}',
    primary key (id)
);

-- Built-in time event types
insert into time_event_types (id, name, description, schema) values
    ('slide', 'Slide', 'Display a slide at this point (link to slide metadata)',
     '{"type":"object","properties":{"transition":{"type":"string","enum":["none","fade","slide"]}}}'::jsonb),
    ('chapter', 'Chapter', 'Chapter marker for navigation',
     '{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}'::jsonb),
    ('caption', 'Caption', 'Subtitle or caption text',
     '{"type":"object","properties":{"text":{"type":"string"},"language":{"type":"string"}},"required":["text"]}'::jsonb),
    ('title', 'Title', 'Change the displayed title',
     '{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}'::jsonb),
    ('action', 'Action', 'Trigger an action during playback',
     '{"type":"object","properties":{"actionType":{"type":"string"},"payload":{"type":"object"}},"required":["actionType"]}'::jsonb);

-- Time Events table
create table time_events (
    id                  uuid not null default gen_random_uuid(),
    metadata_id         uuid not null,
    metadata_version    int not null default 1,
    type                varchar not null,
    start_offset_ms     bigint not null,
    end_offset_ms       bigint,
    sort                int not null default 0,
    linked_metadata_id  uuid,
    attributes          jsonb not null default '{}',
    created             timestamp with time zone default now(),
    modified            timestamp with time zone default now(),
    primary key (id),
    foreign key (metadata_id) references metadata (id) on delete cascade,
    foreign key (linked_metadata_id) references metadata (id) on delete set null,
    foreign key (type) references time_event_types (id)
);

create index ix_time_events_metadata on time_events (metadata_id, metadata_version);
create index ix_time_events_type on time_events (metadata_id, metadata_version, type);
create index ix_time_events_offset on time_events (metadata_id, metadata_version, start_offset_ms);
create index ix_time_events_linked on time_events (linked_metadata_id) where linked_metadata_id is not null;

