create table collection_supplementary
(
    id                uuid                     not null default gen_random_uuid(),
    collection_id     uuid                     not null,
    key               varchar                  not null,
    plan_id           uuid,
    name              varchar                  not null,
    content_type      varchar                  not null,
    content_length    bigint,
    created           timestamp with time zone not null default now(),
    modified          timestamp with time zone not null default now(),
    source_id         uuid,
    source_identifier varchar,
    uploaded          timestamp with time zone,
    attributes        jsonb,
    primary key (id),
    foreign key (collection_id) references collections (id) on delete cascade,
    foreign key (source_id) references sources (id),
    unique (collection_id, key, plan_id)
);

drop table metadata_supplementary_traits;

alter table metadata_supplementary
    drop constraint metadata_supplementary_pkey cascade;
alter table metadata_supplementary
    add column id uuid not null default gen_random_uuid();
alter table metadata_supplementary
    add column plan_id uuid;
alter table metadata_supplementary
    add primary key (id);
alter table metadata_supplementary
    add unique (metadata_id, key, plan_id);

create table metadata_supplementary_traits
(
    id       uuid    not null,
    trait_id varchar not null,
    primary key (id),
    foreign key (id) references metadata_supplementary (id) on delete cascade,
    foreign key (trait_id) references traits (id) on delete cascade
);

create table collection_supplementary_traits
(
    id       uuid    not null,
    trait_id varchar not null,
    primary key (id),
    foreign key (id) references collection_supplementary (id) on delete cascade,
    foreign key (trait_id) references traits (id) on delete cascade
);

alter table collections
    add column public_supplementary boolean not null default false;
