create table metadata_profiles
(
    metadata_id  uuid    not null,
    profile_id   uuid    not null,
    relationship varchar not null,
    sort         int     not null,
    primary key (metadata_id, profile_id, relationship),
    foreign key (metadata_id) references metadata (id) on delete cascade,
    foreign key (profile_id) references profiles (id) on delete cascade
);

create table metadata_version_profiles
(
    metadata_id  uuid    not null,
    version      int     not null,
    profile_id   uuid    not null,
    relationship varchar not null,
    sort         int     not null,
    primary key (metadata_id, version, profile_id, relationship),
    foreign key (metadata_id, version) references metadata_versions (id, version) on delete cascade,
    foreign key (profile_id) references profiles (id)
);

create or replace function metadata_versions_trigger() returns trigger AS
$version_trigger$
begin
    if
        new.version = old.version then
        return new;
    end if;

    insert into metadata_versions (id, version, parent_id, name, content_type, content_length,
                                   attributes, system_attributes,
                                   workflow_state_pending_id, source_id, source_identifier,
                                   delete_workflow_id)
    values (old.id, old.version, old.parent_id, old.name, old.content_type, old.content_length,
            old.attributes, old.system_attributes,
            old.workflow_state_pending_id, old.source_id, old.source_identifier,
            old.delete_workflow_id);

    insert into metadata_version_traits (metadata_id, version, trait_id)
    select metadata_id, new.version, trait_id
    from metadata_traits
    where metadata_id = new.id;

    delete
    from metadata_traits
    where metadata_id = new.id;

    insert into metadata_version_profiles (metadata_id, version, profile_id, relationship, sort)
    select metadata_id, new.version, profile_id, relationship, sort
    from metadata_profiles
    where metadata_id = new.id;

    delete
    from metadata_profiles
    where metadata_id = new.id;

    insert into metadata_version_categories (metadata_id, version, category_id)
    select metadata_id, new.version, category_id
    from metadata_categories
    where metadata_id = new.id;

    delete
    from metadata_categories
    where metadata_id = new.id;

    insert into metadata_versions_supplementary (metadata_id, version, key, name, content_type, content_length, created,
                                                 modified, source_id, source_identifier, uploaded)
    select metadata_id,
           old.version,
           key,
           name,
           content_type,
           content_length,
           created,
           modified,
           source_id,
           source_identifier,
           uploaded
    from metadata_supplementary
    where metadata_id = new.id;

    insert into metadata_version_supplementary_traits (metadata_id, version, key, trait_id)
    select metadata_id, old.version, key, trait_id
    from metadata_supplementary_traits
    where metadata_id = new.id;

    delete
    from metadata_supplementary
    where metadata_id = new.id;

    return new;
end;
$version_trigger$
    language plpgsql;