alter table metadata_versions_supplementary
    add column id uuid,
    add column plan_id uuid,
    add column job_id uuid,
    add column attributes jsonb,
    drop constraint metadata_versions_supplementary_pkey,
    drop constraint metadata_versions_supplementary_metadata_id_key_key;

alter table metadata_versions_supplementary
    add constraint metadata_versions_supplementary_identity_key
        unique nulls not distinct (metadata_id, version, key, id);

alter table metadata_version_supplementary_traits
    add column id uuid,
    drop constraint metadata_version_supplementary_traits_pkey;

alter table metadata_version_supplementary_traits
    add constraint metadata_version_supplementary_traits_identity_key
        unique nulls not distinct (metadata_id, version, key, id, trait_id),
    add foreign key (metadata_id, version, key, id)
        references metadata_versions_supplementary (metadata_id, version, key, id) on delete cascade;

create or replace function metadata_versions_trigger() returns trigger as
$version_trigger$
begin
    if new.version = old.version then
        return new;
    end if;

    insert into metadata_versions (
        id, version, parent_id, name, type, content_type, content_length, language_tag, labels,
        attributes, system_attributes, created, modified, workflow_state_id, workflow_state_pending_id,
        source_id, source_identifier, delete_workflow_id
    ) values (
        old.id, old.version, old.parent_id, old.name, old.type, old.content_type, old.content_length,
        old.language_tag, old.labels, old.attributes, old.system_attributes, old.created, old.modified,
        old.workflow_state_id, old.workflow_state_pending_id, old.source_id, old.source_identifier,
        old.delete_workflow_id
    );

    insert into metadata_version_traits (metadata_id, version, trait_id)
    select metadata_id, old.version, trait_id from metadata_traits where metadata_id = old.id;

    insert into metadata_version_profiles (metadata_id, version, profile_id, relationship, sort)
    select metadata_id, old.version, profile_id, relationship, sort from metadata_profiles where metadata_id = old.id;

    insert into metadata_version_categories (metadata_id, version, category_id)
    select metadata_id, old.version, category_id from metadata_categories where metadata_id = old.id;

    insert into metadata_versions_supplementary (
        id, metadata_id, version, key, name, plan_id, job_id, attributes,
        content_type, content_length, created, modified, source_id, source_identifier, uploaded
    )
    select id, metadata_id, old.version, key, name, plan_id, job_id, attributes,
           content_type, content_length, created, modified, source_id, source_identifier, uploaded
    from metadata_supplementary where metadata_id = old.id;

    insert into metadata_version_supplementary_traits (metadata_id, version, key, id, trait_id)
    select s.metadata_id, old.version, s.key, s.id, t.trait_id
    from metadata_supplementary s join metadata_supplementary_traits t on t.id = s.id
    where s.metadata_id = old.id;

    delete from metadata_traits where metadata_id = old.id;
    delete from metadata_profiles where metadata_id = old.id;
    delete from metadata_categories where metadata_id = old.id;
    delete from metadata_supplementary where metadata_id = old.id;

    return new;
end;
$version_trigger$
    language plpgsql;
