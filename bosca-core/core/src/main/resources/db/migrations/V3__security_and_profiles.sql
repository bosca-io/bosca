create table groups
(
    id          uuid      not null default gen_random_uuid(),
    name        varchar   not null unique,
    description varchar   not null,
    created     timestamp not null default now(),
    enabled     boolean   not null default true,
    primary key (id)
);

insert into groups (name, description)
values ('administrators', 'Bosca Administrators');
insert into groups (name, description)
values ('sa', 'Service Accounts');
insert into groups (name, description)
values ('model.managers', 'Model Managers');
insert into groups (name, description)
values ('workflow.managers', 'Workflow Managers');

create table principals
(
    id         uuid                     not null default gen_random_uuid(),
    created    timestamp with time zone not null default now(),
    modified   timestamp with time zone not null default now(),
    verified   boolean                  not null default false,
    anonymous  boolean                  not null default true,
    attributes jsonb,
    primary key (id)
);

create table principal_groups
(
    principal uuid,
    group_id  uuid,
    primary key (principal, group_id),
    foreign key (principal) references principals (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);

create type principal_credential_type as enum ('password', 'oauth2');

create table principal_credentials
(
    id         bigserial,
    principal  uuid                      not null,
    type       principal_credential_type not null,
    attributes jsonb                     not null,
    primary key (id),
    foreign key (principal) references principals (id) on delete cascade
);

create unique index ix_principal_identifier on principal_credentials ((attributes ->> 'identifier'));

create table profile_attribute_types
(
    id          uuid    not null,
    name        varchar not null,
    description varchar not null,
    primary key (id)
);

create type profile_visibility as enum ('system', 'user', 'friends', 'friends_of_friends', 'public');

create table profiles
(
    id         uuid               not null default gen_random_uuid(),
    principal  uuid,
    name       varchar            not null check (length(name) > 0),
    visibility profile_visibility not null default 'system'::profile_visibility,
    created    timestamp          not null default now(),
    primary key (id),
    foreign key (principal) references principals (id) on delete cascade
);

comment on column profiles.principal is 'this is the identity provider id';

create table profile_attributes
(
    id         uuid               not null,
    profile    uuid               not null,
    type_id    uuid               not null,
    visibility profile_visibility not null default 'system'::profile_visibility,
    value_type varchar            not null,
    value      bytea              not null,
    confidence float              not null,
    priority   int                not null,
    source     varchar            not null,
    created    timestamp          not null default now(),
    expiration timestamp,
    primary key (id),
    foreign key (profile) references profiles (id) on delete cascade,
    foreign key (type_id) references profile_attribute_types (id) on delete cascade
);

create type permission_action as enum ('view', 'edit', 'delete', 'manage', 'list');

create table metadata_permissions
(
    metadata_id uuid              not null,
    group_id    uuid              not null,
    action      permission_action not null,
    primary key (metadata_id, group_id, action),
    foreign key (metadata_id) references metadata (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);

create table collection_permissions
(
    collection_id uuid              not null,
    group_id      uuid              not null,
    action        permission_action not null,
    primary key (collection_id, group_id, action),
    foreign key (collection_id) references collections (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);
