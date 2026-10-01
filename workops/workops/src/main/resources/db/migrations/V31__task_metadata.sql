-- Optional metadata document for tasks.

alter table workops.task add column metadata_id uuid;

create index task_metadata_idx on workops.task(metadata_id) where metadata_id is not null;
