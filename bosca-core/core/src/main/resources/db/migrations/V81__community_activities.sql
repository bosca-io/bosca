create table community_activities
(
    id          uuid    not null default gen_random_uuid(),
    group_id    uuid    not null,
    name        varchar not null,
    description varchar not null,
    type        varchar not null,
    content     jsonb,
    schedule    jsonb,
    primary key (id),
    foreign key (group_id) references community_groups (id) on delete cascade
);
