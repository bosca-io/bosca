create table trait_content_types
(
    trait_id     varchar,
    content_type varchar,
    primary key (trait_id, content_type),
    foreign key (trait_id) references traits (id) on delete cascade
);

create index trait_content_type_ix on trait_content_types (content_type);