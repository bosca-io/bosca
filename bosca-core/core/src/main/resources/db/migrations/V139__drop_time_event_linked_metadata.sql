-- Drop the single-linked metadata columns from time_events, replaced by
-- the time_event_metadata_relationships table (V138) which supports
-- multiple typed relationships per time event.
drop index if exists ix_time_events_linked;
alter table time_events drop column if exists linked_metadata_id;
alter table time_events drop column if exists linked_metadata_version;
