create type group_type as enum ('system', 'principal');

alter table groups add column type group_type not null default 'system'::group_type;