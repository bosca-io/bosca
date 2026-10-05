-- Minimal schema for CI/CD integration tests.
-- Creates only the tables/types the CI migrations reference via foreign keys.

create schema if not exists git;

-- Core prerequisites the CI migrations reference (normally created by core migrations): the shared
-- permission enum and the groups table V34's secret-permission FK points at. These live in the
-- PUBLIC schema, which survives the per-run `drop schema git cascade` on the reused container —
-- so both must be idempotent.
do $$ begin
    if not exists (select 1 from pg_type where typname = 'permission_action') then
        create type permission_action as enum ('view', 'edit', 'delete', 'manage', 'list', 'execute', 'impersonate');
    end if;
end $$;

create table if not exists groups (
    id uuid primary key default gen_random_uuid()
);

do $$ begin
    if not exists (select 1 from pg_type where typname = 'principal_credential_type') then
        create type principal_credential_type as enum ('api_token');
    end if;
end $$;

create table if not exists principals (
    id uuid primary key default gen_random_uuid()
);

create table if not exists principal_credentials (
    id bigserial primary key,
    principal uuid not null references principals (id) on delete cascade,
    type principal_credential_type not null,
    attributes jsonb not null
);

create type git.visibility as enum ('public', 'internal', 'private');

create table git.repositories (
    id              uuid primary key default gen_random_uuid(),
    slug            varchar not null,
    name            varchar not null,
    owner_id        uuid not null,
    visibility      git.visibility not null default 'private',
    default_branch  varchar not null default 'main',
    archived        boolean not null default false,
    deleted         boolean not null default false,
    disk_size_bytes bigint not null default 0,
    created         timestamptz not null default now(),
    updated         timestamptz not null default now()
);
