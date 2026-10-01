drop table if exists collection_collaborations;
create table collection_collaborations
(
    collection_id uuid                     not null,
    language_tag  varchar                  not null,
    content       bytea                    not null,
    created       timestamp with time zone not null default now(),
    modified      timestamp with time zone not null default now(),
    primary key (collection_id, language_tag),
    foreign key (collection_id) references collections (id)
        on delete cascade
);

