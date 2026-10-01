create table organizations
(
    id                uuid                     not null default gen_random_uuid(),
    name              varchar                  not null,
    attributes        jsonb                    not null,
    system_attributes jsonb                    not null,
    profile_id        uuid                     not null,
    visibility        profile_visibility       not null,
    created           timestamp with time zone not null,
    modified          timestamp with time zone not null,
    primary key (id),
    foreign key (profile_id) references profiles (id)
);

create table organization_signup_tokens
(
    token           varchar                  not null,
    organization_id uuid                     not null,
    group_id        uuid,
    created         timestamp with time zone not null default now(),
    expires         timestamp with time zone not null default now() + interval '60 day',
    primary key (token),
    foreign key (organization_id) references organizations (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);

create table organization_signup_email
(
    email           varchar                  not null,
    organization_id uuid                     not null,
    group_id        uuid,
    created         timestamp with time zone not null default now(),
    expires         timestamp with time zone not null default now() + interval '60 day',
    primary key (email),
    foreign key (organization_id) references organizations (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);

create table organization_permissions
(
    organization_id uuid              not null,
    group_id        uuid              not null,
    action          permission_action not null,
    primary key (organization_id, group_id, action),
    foreign key (organization_id) references organizations (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);

create table organization_domains
(
    organization_id uuid    not null,
    domain          varchar not null,
    auto_join       boolean not null default true,
    group_id        uuid,
    primary key (organization_id, domain),
    foreign key (organization_id) references organizations (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);

create table organization_members
(
    organization_id uuid                     not null,
    principal_id    uuid                     not null,
    created         timestamp with time zone not null,
    primary key (organization_id, principal_id),
    foreign key (organization_id) references organizations (id) on delete cascade,
    foreign key (principal_id) references principals (id) on delete cascade
);

create index domain_ix on organization_domains (domain);
