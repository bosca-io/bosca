-- Work Ops — Phase 24 (specs/workops/plan.md §24, R37)
--
-- Federation primitives: peer connection rows, per-(project, peer)
-- field masks, conflict audit, raw payload archive, and the
-- additive task / comment columns that mirror the remote
-- canonical id, key, URL, etag, and field-mask state.

create table workops.federation_peer (
    id                              uuid    not null default gen_random_uuid() primary key,
    name                            varchar not null unique,
    description                     varchar,
    kind                            varchar not null,
    base_url                        varchar not null,
    -- Discriminated FederationAuth blob.
    auth_payload                    jsonb   not null default '{}'::jsonb,
    principal_mapping_policy        varchar not null default 'EMAIL_OR_PLACEHOLDER',
    sync_interval_seconds           integer not null default 60,
    enabled                         boolean not null default true,
    last_sync_at                    timestamptz,
    last_sync_etag                  varchar,
    version                         bigint  not null default 0
);

create index federation_peer_kind_idx on workops.federation_peer(kind);
create index federation_peer_enabled_idx on workops.federation_peer(enabled) where enabled = true;

create table workops.federation_field_mask (
    project_id      uuid    not null references workops.project(id) on delete cascade,
    peer_id         uuid    not null references workops.federation_peer(id) on delete cascade,
    mask            bigint  not null default 0,
    primary key (project_id, peer_id)
);

create table workops.federation_conflict (
    id              uuid    not null default gen_random_uuid() primary key,
    task_id         uuid    not null references workops.task(id) on delete cascade,
    peer_id         uuid    not null references workops.federation_peer(id) on delete cascade,
    field_key       varchar not null,
    old_value       jsonb   not null default '{}'::jsonb,
    new_value       jsonb   not null default '{}'::jsonb,
    resolution      varchar not null default 'UNRESOLVED',
    triggered_by    varchar not null,
    created_at      timestamptz not null default now(),
    resolved_at     timestamptz
);

create index federation_conflict_task_idx
    on workops.federation_conflict(task_id, created_at desc);
create index federation_conflict_unresolved_idx
    on workops.federation_conflict(peer_id) where resolution = 'UNRESOLVED';

-- Inbound principal mapping proposals: remote user → local
-- profile, awaiting admin acceptance under MANUAL_MAPPING_REQUIRED.
create table workops.federation_principal_mapping_proposal (
    peer_id                 uuid    not null references workops.federation_peer(id) on delete cascade,
    remote_user_id          varchar not null,
    proposed_profile_id     uuid,
    remote_email            varchar,
    proposed_at             timestamptz not null default now(),
    accepted_at             timestamptz,
    primary key (peer_id, remote_user_id)
);

-- Raw inbound payload archive — partitioned by month, same
-- retention policy as task_history.
create table workops.federation_payload_archive (
    id              uuid    not null default gen_random_uuid(),
    peer_id         uuid    not null references workops.federation_peer(id) on delete cascade,
    received_at     timestamptz not null default now(),
    direction       varchar not null check (direction in ('INBOUND', 'OUTBOUND')),
    -- The raw payload — encrypted at rest in production.
    payload         jsonb   not null,
    primary key (id, received_at)
) partition by range (received_at);

-- Pre-create the rolling window so the partition manager has
-- something to detach. The audit-retention service reuses the
-- task_history pattern for rotation.
do $$
declare
    yyyymm text;
    start_ts timestamptz;
    end_ts   timestamptz;
begin
    for i in 0..12 loop
        yyyymm   := to_char(date_trunc('month', now()) + (i || ' months')::interval, 'YYYYMM');
        start_ts := date_trunc('month', now()) + (i || ' months')::interval;
        end_ts   := date_trunc('month', now()) + ((i + 1) || ' months')::interval;
        execute format(
            $f$create table if not exists workops.federation_payload_archive_%s
                  partition of workops.federation_payload_archive
                  for values from ('%s') to ('%s')$f$,
            yyyymm, start_ts, end_ts
        );
    end loop;
end $$;

create index federation_payload_archive_peer_idx
    on workops.federation_payload_archive(peer_id, received_at desc);

-- Additive task columns. The mirrored task is indistinguishable
-- from a local task at the data layer; only `federation_peer_id`
-- distinguishes it.
alter table workops.task
    add column if not exists federation_peer_id          uuid references workops.federation_peer(id) on delete set null,
    add column if not exists federation_remote_id        varchar,
    add column if not exists federation_remote_key       varchar,
    add column if not exists federation_canonical_url    varchar,
    add column if not exists federation_last_sync_at     timestamptz,
    add column if not exists federation_last_sync_etag   varchar,
    add column if not exists federation_field_mask       bigint not null default 0,
    add column if not exists federation_extra_fields     jsonb  not null default '{}'::jsonb;

create index task_federation_peer_idx
    on workops.task(federation_peer_id) where federation_peer_id is not null;
create unique index task_federation_remote_idx
    on workops.task(federation_peer_id, federation_remote_id)
    where federation_peer_id is not null and federation_remote_id is not null;
