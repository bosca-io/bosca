create type data_type as enum ('attributes', 'table');
alter table data_templates add column type data_type not null default 'attributes';
