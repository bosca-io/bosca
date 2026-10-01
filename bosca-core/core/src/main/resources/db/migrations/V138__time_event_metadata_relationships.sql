-- Relationships between time events and metadata items, allowing each time event
-- to link to multiple metadata items with different relationship types (e.g. 'slide', 'resource', 'reference')
create table time_event_metadata_relationships (
    time_event_id           uuid not null,
    metadata_id             uuid not null,
    metadata_version        int,
    relationship            varchar not null,
    attributes              jsonb,
    primary key (time_event_id, metadata_id, relationship),
    foreign key (time_event_id) references time_events (id) on delete cascade,
    foreign key (metadata_id) references metadata (id) on delete cascade
);

create index ix_time_event_metadata_relationships_event on time_event_metadata_relationships (time_event_id);
create index ix_time_event_metadata_relationships_metadata on time_event_metadata_relationships (metadata_id);
