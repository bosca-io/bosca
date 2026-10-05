create table prayer_community_groups
(
    prayer_id          uuid not null,
    community_group_id uuid not null,
    primary key (prayer_id, community_group_id),
    foreign key (prayer_id) references prayers (id) on delete cascade,
    foreign key (community_group_id) references community_groups (id) on delete cascade
);

create index idx_prayer_community_groups_community_group_id on prayer_community_groups (community_group_id);
