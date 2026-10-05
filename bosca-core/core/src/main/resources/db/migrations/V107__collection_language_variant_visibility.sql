alter table collection_language_variants add column public boolean not null default false;
alter table collection_language_variants add column public_list boolean not null default false;
alter table collection_language_variants add column public_supplementary boolean not null default false;
alter table collection_language_variants add column searchable boolean not null default true;
