-- Gateway — HTTP Auth Gateway service/route management.
--
-- Tables live in a dedicated `gateway` schema, isolated from `public`
-- so cross-schema permissions can diverge and module uninstall is a
-- single `drop schema gateway cascade`.

create schema if not exists gateway;

-- Postgres-native enums give us catalog-level type safety: a stray
-- string can't be written, and ALTER TYPE is one statement when the
-- domain grows. The Rust proxy keeps its own copy of the value set
-- (#[serde(rename_all = "lowercase")]); both sides MUST stay in sync.
create type gateway.auth_method as enum ('oauth2', 'jwt', 'basic', 'none');
create type gateway.health_status as enum ('up', 'down', 'unknown');

create table gateway.service (
    id                          uuid                    not null default gen_random_uuid() primary key,
    name                        varchar                 not null unique,
    url                         varchar                 not null,
    health_check_path           varchar,
    health_check_interval_secs  int                     not null default 30,
    connect_timeout_secs        int                     not null default 5,
    request_timeout_secs        int                     not null default 300,
    pool_max_idle               int                     not null default 10,
    pool_idle_timeout_secs      int                     not null default 90,
    enabled                     boolean                 not null default true,
    public                      boolean                 not null default false,
    public_content              boolean                 not null default false,
    public_list                 boolean                 not null default false,
    public_supplementary        boolean                 not null default false,
    -- Upstream health, reported by the proxy fleet on state transitions
    -- only (the proxy runs its own state machine — see proxy/src/health
    -- — and posts to /api/v1/gateway/services/{id}/health when the
    -- status flips). The DB row is the latest-wins aggregate across
    -- proxy replicas; it is NOT authoritative for routing decisions
    -- (the proxy decides what to do with a probe failure locally).
    -- `unknown` is the seed value for upstreams that have not yet been
    -- probed (e.g., right after creation, or when no health_check_path
    -- is configured).
    health_status               gateway.health_status   not null default 'unknown',
    health_status_changed_at    timestamptz             not null default now(),
    health_status_reason        varchar,
    created_at                  timestamptz             not null default now(),
    modified_at                 timestamptz             not null default now(),
    deleted_at                  timestamptz,
    version                     bigint                  not null default 0
);

create index gateway_name_idx     on gateway.service(name)    where deleted_at is null;
create index gateway_enabled_idx  on gateway.service(enabled) where deleted_at is null;

-- Per-entity permission ACL. Auditable: every row records WHO granted
-- the permission and WHEN, so an incident response can trace privilege
-- back to its source.
create table gateway.permission (
    gateway_id  uuid        not null references gateway.service(id) on delete cascade,
    group_id    uuid        not null references groups(id) on delete cascade,
    action      varchar     not null,
    granted_by  uuid        not null references profiles(id),
    granted_at  timestamptz not null default now(),
    primary key (gateway_id, group_id, action)
);

create index gateway_permission_gateway_idx on gateway.permission(gateway_id);
create index gateway_permission_group_idx   on gateway.permission(group_id);

create table gateway.route (
    id                  uuid                    not null default gen_random_uuid() primary key,
    gateway_id          uuid                    not null references gateway.service(id) on delete restrict,
    path_pattern        varchar                 not null,
    auth_method         gateway.auth_method     not null,
    strip_prefix        boolean                 not null default false,
    read_groups         varchar[]               not null default '{}',
    write_groups        varchar[]               not null default '{}',
    inject_headers      jsonb                   not null default '{}',
    sort_order          int                     not null default 0,
    enabled             boolean                 not null default true,
    created_at          timestamptz             not null default now(),
    modified_at         timestamptz             not null default now(),
    deleted_at          timestamptz,
    version             bigint                  not null default 0,
    -- An unauthenticated route (`none`) bypasses group gating entirely
    -- — the proxy has no principal to compare against. Configuring
    -- `read_groups` or `write_groups` on a `none` route is almost
    -- always operator error: the lists are silently ignored, giving
    -- a false sense of access control. Reject at the database boundary.
    constraint gateway_route_none_no_groups
        check (
            auth_method <> 'none'
            or (array_length(read_groups, 1) is null and array_length(write_groups, 1) is null)
        )
);

create index gateway_route_gateway_idx  on gateway.route(gateway_id)  where deleted_at is null;
create index gateway_route_enabled_idx  on gateway.route(enabled)     where deleted_at is null;
create index gateway_route_sort_idx     on gateway.route(sort_order)  where deleted_at is null;
create unique index gateway_route_pattern_unique
    on gateway.route(gateway_id, path_pattern)
    where deleted_at is null;

-- Config version tracking — bumped application-side in every mutation transaction.
create table gateway.config_version (
    id          int         not null primary key default 1 check (id = 1),
    version     varchar     not null default gen_random_uuid()::text,
    updated_at  timestamptz not null default now()
);

insert into gateway.config_version (id) values (1);
