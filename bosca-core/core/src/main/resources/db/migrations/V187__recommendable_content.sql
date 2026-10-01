alter table metadata add column recommendable boolean not null default true;

alter table collections add column recommendable boolean not null default true;

alter table collection_language_variants add column recommendable boolean not null default true;
