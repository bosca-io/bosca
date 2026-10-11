create table bible_providers.sources (
    id uuid primary key,
    configuration_id uuid not null references public.configurations (id) on delete cascade,
    provider varchar not null,
    provider_bible_id varchar not null,
    metadata_id uuid not null,
    metadata_version integer not null,
    variant varchar not null,
    kind varchar not null check (kind in ('text', 'audio')),
    name varchar not null,
    name_local varchar not null,
    abbreviation varchar not null,
    attribution jsonb not null,
    allowed_uses jsonb not null check (jsonb_typeof(allowed_uses) = 'array'),
    version bigint not null default 0,
    created timestamptz not null default now(),
    modified timestamptz not null default now(),
    deleted_at timestamptz,
    foreign key (metadata_id, metadata_version, variant)
        references public.bibles (metadata_id, version, variant) deferrable initially deferred
);

create unique index sources_active_text on bible_providers.sources (metadata_id, metadata_version, variant)
    where kind = 'text' and deleted_at is null;
create index sources_configuration on bible_providers.sources (configuration_id) where deleted_at is null;
create index sources_audio on bible_providers.sources (metadata_id, metadata_version, variant)
    where kind = 'audio' and deleted_at is null;
