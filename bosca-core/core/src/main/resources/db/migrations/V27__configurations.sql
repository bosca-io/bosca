create table configurations
(
    id          uuid    not null default gen_random_uuid(),
    key         varchar not null,
    description varchar not null,
    primary key (id)
);

alter table configurations
    add constraint key_ix unique (key);

create table configuration_values
(
    configuration_id uuid  not null,
    value            bytea not null,
    nonce            bytea not null,
    primary key (configuration_id),
    foreign key (configuration_id) references configurations (id) on delete cascade
);

create table configuration_permissions
(
    entity_id uuid              not null,
    group_id  uuid              not null,
    action    permission_action not null,
    primary key (entity_id, group_id, action),
    foreign key (entity_id) references configurations (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);

alter type permission_action add value 'impersonate' after 'execute';
