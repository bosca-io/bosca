create table collection_variant_metadata_relationships
(
    collection_id uuid    not null,
    language_tag  varchar not null,
    metadata_id   uuid    not null,
    relationship  varchar,
    attributes    jsonb,
    primary key (collection_id, language_tag, metadata_id, relationship),
    foreign key (collection_id, language_tag) references collection_language_variants (id, language_tag) on delete cascade,
    foreign key (metadata_id) references metadata (id) on delete cascade
);
