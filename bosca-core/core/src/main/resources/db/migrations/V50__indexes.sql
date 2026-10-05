create index if not exists slugs_metadata_id_index
    on slugs (metadata_id);

create index if not exists slugs_collection_id_index
    on slugs (collection_id);

create index if not exists slugs_profile_id_index
    on slugs (profile_id);

create index if not exists profiles_principal_id_index
    on profiles (principal);

