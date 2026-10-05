create schema if not exists pipelines;

create table pipelines.pipelines (
    id                  uuid primary key default gen_random_uuid(),
    name                text not null,
    description         text not null default '',
    accepted_input_type text not null,
    graph               jsonb not null default '{"nodes":[],"edges":[]}'::jsonb,
    version             bigint not null default 0,
    deleted_at          timestamptz,
    created_at          timestamptz not null default now(),
    modified_at         timestamptz not null default now()
);

-- Consumers filter pipelines by the type they accept (e.g. an event fqdn).
create index ix_pipelines_accepted_input_type
    on pipelines.pipelines (accepted_input_type)
    where deleted_at is null;
