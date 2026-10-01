create table profile_relationships
(
    profile_id_1 uuid    not null,
    profile_id_2 uuid    not null,
    type         varchar not null,
    attributes   jsonb,
    primary key (profile_id_1, profile_id_2, type),
    foreign key (profile_id_1) references profiles (id) on delete cascade,
    foreign key (profile_id_2) references profiles (id) on delete cascade
);

create type community_visibility as enum ('public', 'private', 'hidden');

create table community_groups
(
    id          uuid                 not null default gen_random_uuid(),
    name        varchar              not null,
    description varchar              not null,
    visibility  community_visibility not null,
    attributes  jsonb,
    primary key (id)
);

create table community_group_members
(
    group_id   uuid not null,
    profile_id uuid not null,
    primary key (group_id, profile_id),
    foreign key (group_id) references community_groups (id) on delete cascade,
    foreign key (profile_id) references profiles (id) on delete cascade
);

create type chat_channel_type as enum ('direct', 'group', 'public');

create table chat_channels
(
    id         uuid              not null default gen_random_uuid(),
    group_id   uuid,
    name       varchar           not null,
    type       chat_channel_type not null,
    attributes jsonb,
    primary key (id),
    foreign key (group_id) references community_groups (id) on delete cascade
);

create table chat_channel_members
(
    channel_id         uuid    not null,
    profile_id         uuid    not null,
    role               varchar not null,
    last_read_at       timestamp with time zone,
    last_read_sequence bigint,
    attributes         jsonb,
    primary key (channel_id, profile_id),
    foreign key (channel_id) references chat_channels (id) on delete cascade,
    foreign key (profile_id) references profiles (id) on delete cascade
);
