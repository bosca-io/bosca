create schema if not exists localization;

-- A localization project groups related strings (e.g., "ios-app", "web-dashboard")
create table localization.projects
(
    id              uuid        not null default gen_random_uuid(),
    name            varchar     not null,
    description     varchar,
    source_language varchar     not null default 'en',
    attributes      jsonb,
    created         timestamp with time zone not null default now(),
    modified        timestamp with time zone not null default now(),
    primary key (id),
    foreign key (source_language) references public.languages (tag)
);

-- A string key within a project (e.g., "greeting", "items_count")
create table localization.strings
(
    id              uuid        not null default gen_random_uuid(),
    project_id      uuid        not null references localization.projects (id) on delete cascade,
    key             varchar     not null,
    context         varchar,
    metadata_id     uuid,
    placeholders    jsonb,
    max_length      int,
    tags            varchar[]   not null default '{}',
    plural          boolean     not null default false,
    created         timestamp with time zone not null default now(),
    modified        timestamp with time zone not null default now(),
    primary key (id),
    unique (project_id, key)
);
create index idx_loc_strings_project on localization.strings (project_id);
create index idx_loc_strings_metadata on localization.strings (metadata_id) where metadata_id is not null;

-- Translation origin tracking: who or what produced a translation
create type localization.translation_origin as enum ('human', 'ai', 'import', 'sync');

-- Translation workflow states
create type localization.translation_state as enum (
    'draft',
    'ai_generated',
    'in_review',
    'approved',
    'rejected',
    'published',
    'archived'
);

-- Translations for each string + language (plain strings)
create table localization.translations
(
    id              uuid        not null default gen_random_uuid(),
    string_id       uuid        not null references localization.strings (id) on delete cascade,
    language_tag    varchar     not null references public.languages (tag),
    text            varchar     not null,
    state           localization.translation_state not null default 'draft',
    origin          localization.translation_origin not null default 'human',
    origin_detail   varchar,
    reviewed_by     uuid,
    reviewed_at     timestamp with time zone,
    created_by      uuid,
    created         timestamp with time zone not null default now(),
    modified        timestamp with time zone not null default now(),
    primary key (id),
    unique (string_id, language_tag)
);
create index idx_loc_translations_string on localization.translations (string_id);
create index idx_loc_translations_language on localization.translations (language_tag);
create index idx_loc_translations_state on localization.translations (state);

-- Plural form translations (one row per plural category per language)
create table localization.plural_translations
(
    id              uuid        not null default gen_random_uuid(),
    string_id       uuid        not null references localization.strings (id) on delete cascade,
    language_tag    varchar     not null references public.languages (tag),
    plural_category varchar     not null,
    text            varchar     not null,
    state           localization.translation_state not null default 'draft',
    origin          localization.translation_origin not null default 'human',
    origin_detail   varchar,
    reviewed_by     uuid,
    reviewed_at     timestamp with time zone,
    created_by      uuid,
    created         timestamp with time zone not null default now(),
    modified        timestamp with time zone not null default now(),
    primary key (id),
    unique (string_id, language_tag, plural_category)
);
create index idx_loc_plural_translations_string on localization.plural_translations (string_id);

-- Links metadata documents to localization projects for translation management.
create table localization.project_documents
(
    id              uuid        not null default gen_random_uuid(),
    project_id      uuid        not null references localization.projects (id) on delete cascade,
    metadata_id     uuid        not null,
    attributes      jsonb,
    created         timestamp with time zone not null default now(),
    modified        timestamp with time zone not null default now(),
    primary key (id),
    unique (project_id, metadata_id)
);
create index idx_loc_project_documents_project on localization.project_documents (project_id);
create index idx_loc_project_documents_metadata on localization.project_documents (metadata_id);

-- Translated document content per language, stored as JSONB.
create table localization.document_translations
(
    id              uuid        not null default gen_random_uuid(),
    document_id     uuid        not null references localization.project_documents (id) on delete cascade,
    language_tag    varchar     not null references public.languages (tag),
    content         jsonb       not null,
    state           localization.translation_state not null default 'draft',
    origin          localization.translation_origin not null default 'human',
    origin_detail   varchar,
    reviewed_by     uuid,
    reviewed_at     timestamp with time zone,
    created_by      uuid,
    created         timestamp with time zone not null default now(),
    modified        timestamp with time zone not null default now(),
    primary key (id),
    unique (document_id, language_tag)
);
create index idx_loc_doc_translations_document on localization.document_translations (document_id);
create index idx_loc_doc_translations_language on localization.document_translations (language_tag);
create index idx_loc_doc_translations_state on localization.document_translations (state);

-- Project-scoped permissions using Bosca's EntityPermission pattern.
create table localization.project_permissions
(
    id              uuid        not null default gen_random_uuid(),
    project_id      uuid        not null references localization.projects (id) on delete cascade,
    group_id        uuid        not null,
    action          varchar     not null,
    primary key (id),
    unique (project_id, group_id, action)
);
create index idx_loc_project_permissions_project on localization.project_permissions (project_id);
create index idx_loc_project_permissions_group on localization.project_permissions (group_id);

-- Crowdin sync state tracking
create table localization.sync_state
(
    id              uuid        not null default gen_random_uuid(),
    project_id      uuid        not null references localization.projects (id) on delete cascade,
    provider        varchar     not null default 'crowdin',
    external_id     varchar,
    last_synced     timestamp with time zone,
    sync_config     jsonb,
    attributes      jsonb,
    primary key (id),
    unique (project_id, provider)
);

-- Audit log: tracks every state transition for translations
create table localization.translation_history
(
    id              uuid        not null default gen_random_uuid(),
    translation_id  uuid        not null,
    table_name      varchar     not null,
    from_state      localization.translation_state,
    to_state        localization.translation_state not null,
    changed_by      uuid,
    origin          localization.translation_origin not null,
    origin_detail   varchar,
    previous_text   varchar,
    new_text        varchar     not null,
    created         timestamp with time zone not null default now(),
    primary key (id)
);
create index idx_loc_history_translation on localization.translation_history (translation_id);
create index idx_loc_history_created on localization.translation_history (created);
