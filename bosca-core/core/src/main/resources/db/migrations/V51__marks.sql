alter table profile_bookmarks
    add column attributes jsonb;

create table profile_marks
(
    id               bigserial not null,
    profile_id       uuid      not null,
    metadata_id      uuid,
    metadata_version int,
    collection_id    uuid,
    attributes       jsonb,
    created          timestamp with time zone default now(),
    primary key (id),
    foreign key (profile_id) references profiles (id) on delete cascade,
    foreign key (metadata_id) references metadata (id) on delete cascade,
    foreign key (collection_id) references collections (id) on delete cascade,
    check ( (metadata_id is not null and metadata_version is not null) or collection_id is not null )
);