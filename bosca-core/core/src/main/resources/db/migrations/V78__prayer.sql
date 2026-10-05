create type prayer_status as enum ('active', 'cancelled', 'answered', 'pending', 'blocked', 'pending_approval');

create table prayers
(
    id         uuid                     not null default gen_random_uuid(),
    profile_id uuid                     not null,
    content    jsonb                    not null,
    status     prayer_status            not null default 'active',
    created    timestamp with time zone not null default now(),
    modified   timestamp with time zone not null default now(),
    attributes jsonb,
    primary key (id),
    foreign key (profile_id) references profiles (id) on delete cascade
);

create table prayer_permissions
(
    prayer_id uuid              not null,
    group_id  uuid              not null,
    action    permission_action not null,
    primary key (prayer_id, group_id, action),
    foreign key (prayer_id) references prayers (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);

create table prayer_comments
(
    parent_id         bigint,
    id                bigserial,
    prayer_id         uuid    not null,
    profile_id        uuid    not null,
    impersonator_id   uuid,
    visibility        profile_visibility       default 'user',
    created           timestamp with time zone default now(),
    modified          timestamp with time zone default now(),
    status            comment_status           default 'pending'::comment_status,
    content           text    not null check (length(content) > 0),
    attributes        jsonb,
    system_attributes jsonb,
    has_replies       boolean not null         default false,
    deleted           boolean                  default false,
    primary key (id),
    foreign key (prayer_id) references prayers (id),
    foreign key (profile_id) references profiles (id),
    foreign key (impersonator_id) references profiles (id),
    foreign key (parent_id) references prayer_comments (id)
);
