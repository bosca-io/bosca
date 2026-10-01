-- Kubernetes — V1
--
-- Establishes the dedicated `kubernetes` Postgres schema for cluster
-- registrations and their encrypted kubeconfigs. Subsequent migrations
-- can layer on per-cluster cached state (last-observed namespaces, node
-- snapshots, etc.) without colliding with `public` or other subsystems.
--
-- Kubeconfigs are NEVER stored in plaintext — `cluster_credential` holds
-- the (nonce, ciphertext) pair produced by `bosca.security.encryption.
-- EncryptionService.encrypt(...)`, keyed on cluster id. Application
-- code must decrypt through the service so audit hooks fire.

create schema if not exists kubernetes;

create table kubernetes.cluster (
    id              uuid primary key,
    name            text        not null,
    provider        text        not null,
    region          text        not null,
    environment     text        not null check (environment in ('PRODUCTION', 'STAGING', 'DEVELOPMENT')),
    server_version  text        not null default '',
    health          text        not null default 'OK' check (health in ('OK', 'WARN', 'ERROR')),
    nodes           int         not null default 0,
    pods            int         not null default 0,
    registered_at   timestamptz not null default now(),
    last_seen_at    timestamptz,
    deleted_at      timestamptz,
    modified_at     timestamptz not null default now(),
    created_at      timestamptz not null default now(),
    version         bigint      not null default 0
);

create unique index cluster_name_active_idx
    on kubernetes.cluster (name)
    where deleted_at is null;

create index cluster_environment_idx
    on kubernetes.cluster (environment)
    where deleted_at is null;

-- Encrypted kubeconfig storage. One credential row per cluster.
-- Decoupled from `cluster` so re-keying / credential rotation can churn
-- without touching the parent row's optimistic-lock version.
create table kubernetes.cluster_credential (
    cluster_id  uuid primary key references kubernetes.cluster (id) on delete cascade,
    nonce       bytea       not null,
    data        bytea       not null,
    created_at  timestamptz not null default now(),
    modified_at timestamptz not null default now()
);
