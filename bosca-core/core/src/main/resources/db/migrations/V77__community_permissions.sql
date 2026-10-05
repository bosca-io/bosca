create table community_group_permissions
(
    community_group_id uuid              not null,
    group_id           uuid              not null,
    action             permission_action not null,
    primary key (community_group_id, group_id, action),
    foreign key (community_group_id) references community_groups (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);

create table chat_channel_permissions
(
    channel_id uuid              not null,
    group_id   uuid              not null,
    action     permission_action not null,
    primary key (channel_id, group_id, action),
    foreign key (channel_id) references chat_channels (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);
