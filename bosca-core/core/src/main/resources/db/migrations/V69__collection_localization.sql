create table collection_language_variants
(
    id           uuid    not null,
    language_tag varchar not null,
    name         varchar not null,
    description  varchar,
    attributes   json,
    primary key (id, language_tag),
    foreign key (id) references collections (id) on delete cascade
);

alter table collections
    add column language_tag varchar not null default 'en';