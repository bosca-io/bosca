alter table collections
    drop constraint collections_template_metadata_id_template_metadata_version_fkey;

alter table collections
    add foreign key (template_metadata_id, template_metadata_version) references collection_templates;

