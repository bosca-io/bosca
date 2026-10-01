create type form_schema_type as enum ('submission', 'internal');

alter table form_schemas add column type form_schema_type not null default 'internal';
