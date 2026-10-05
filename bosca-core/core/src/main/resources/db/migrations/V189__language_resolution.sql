create table language_resolution_contexts
(
    id                    uuid primary key default gen_random_uuid(),
    key                   varchar not null unique,
    name                  varchar not null,
    description           varchar not null default '',
    fallback_language_tag varchar not null references languages (tag)
);

create table language_tag_mappings
(
    context_id           uuid    not null references language_resolution_contexts (id) on delete cascade,
    source_language_tag  varchar not null,
    resolved_language_tag varchar not null references languages (tag),
    primary key (context_id, source_language_tag)
);

create index language_tag_mappings_resolved_idx
    on language_tag_mappings (context_id, resolved_language_tag);
