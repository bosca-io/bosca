alter table profile_attribute_types
    add column form_schema_id uuid;

alter table profile_attribute_types
    add foreign key (form_schema_id) references form_schemas (id) on delete set null;
