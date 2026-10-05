alter table collection_templates drop column collection_filter;
alter table collection_templates drop column metadata_filter;
alter table collection_templates add column filters jsonb not null default '{}';