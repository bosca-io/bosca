create type community_group_type as enum ('family', 'small_group', 'custom');

alter table community_groups add column type community_group_type not null default 'custom';
