-- Content entity links — materialized index of entity references
-- extracted from Tiptap/ProseMirror document nodes. Rebuilt on
-- document save; enables graph queries like "all documents that
-- reference metadata X" without JSONB deep-scans.

create type content_link_target as enum (
    'metadata', 'collection', 'profile', 'task', 'spec',
    'requirement', 'project', 'program', 'git_repository',
    'chat_channel', 'ai_session', 'external_uri'
);

create table content_entity_link (
    id                  uuid        not null default gen_random_uuid() primary key,
    metadata_id         uuid        not null,
    metadata_version    int         not null,
    target_type         content_link_target not null,
    target_id           varchar     not null,
    node_type           varchar,
    position            jsonb,
    created_at          timestamptz not null default now()
);

create index content_entity_link_source_idx on content_entity_link(metadata_id, metadata_version);
create index content_entity_link_target_idx on content_entity_link(target_type, target_id);
