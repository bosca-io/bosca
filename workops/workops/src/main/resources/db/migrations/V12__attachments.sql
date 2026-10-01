-- Work Ops — Phase 8.4 (specs/workops/plan.md §8.4, R16)
--
-- Attachments. Per-attachment + per-task aggregate limits live on
-- the project row so admins can override per-project. The
-- storage_object_id is the FK into core-storage; null until the
-- delivery worker registers the upload (Phase 11).

create table workops.attachment (
    id                              uuid    not null default gen_random_uuid() primary key,
    task_id                         uuid    not null references workops.task(id) on delete cascade,
    storage_object_id               uuid,
    filename                        varchar not null,
    content_type                    varchar not null,
    size_bytes                      bigint  not null check (size_bytes > 0),
    thumbnail_storage_object_id     uuid,
    uploaded_by_profile_id          uuid    not null,
    uploaded_at                     timestamptz not null default now(),
    description                     varchar,
    deleted_at                      timestamptz
);

create index attachment_task_idx on workops.attachment(task_id) where deleted_at is null;
create index attachment_storage_idx on workops.attachment(storage_object_id) where storage_object_id is not null;

alter table workops.project
    add column if not exists max_attachment_bytes        bigint  not null default 52428800;  -- 50MB
alter table workops.project
    add column if not exists max_total_attachment_bytes  bigint  not null default 1073741824; -- 1GB
-- Rejected content types in addition to the defaults
-- (application/x-msdownload, x-msdos-program, x-sh, x-bat).
alter table workops.project
    add column if not exists attachment_content_type_denylist varchar[] not null default array[]::varchar[];
