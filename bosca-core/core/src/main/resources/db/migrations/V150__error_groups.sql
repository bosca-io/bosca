create type error_group_status as enum (
    'open',
    'resolved',
    'ignored'
);

-- Per-fingerprint aggregate rows for the error tracking subsystem.
-- See backend/framework/analytics/src/main/kotlin/bosca/analytics/repository/ErrorGroupRepository.kt
-- for the full upsert semantics and on-conflict behavior.
create table error_groups
(
    -- SHA-256-based 32-hex-char fingerprint (128 bits). Always that exact length.
    fingerprint     varchar(32)         not null,
    app_id          varchar             not null,
    type            varchar             not null,
    message         varchar             not null,
    fatal           boolean             not null,
    status          error_group_status  not null default 'open',
    assignee_id     uuid                null,
    first_seen      timestamptz         not null,
    last_seen       timestamptz         not null,
    event_count     bigint              not null default 0,
    sample_event_id varchar             null,
    sample_stack    varchar             null,
    ai_summary      varchar             null,
    ai_summary_at   timestamptz         null,
    created         timestamptz         not null default now(),
    modified        timestamptz         not null default now(),
    primary key (fingerprint)
);

-- Default list view: filter by app + status, sort by last_seen desc.
create index error_groups_app_status_last_seen_idx
    on error_groups (app_id, status, last_seen desc);

-- "Top errors" view: filter by app, sort by count desc.
create index error_groups_app_count_idx
    on error_groups (app_id, event_count desc);

-- Global list view (no app filter): sort by last_seen desc.
create index error_groups_last_seen_idx
    on error_groups (last_seen desc);

-- "My assigned errors" view: filter by assignee, sort by last_seen desc.
create index error_groups_assignee_id_idx
    on error_groups (assignee_id, last_seen desc);
