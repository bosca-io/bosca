create type comment_status as enum ('pending', 'blocked', 'pending_approval', 'approved');

create table metadata_comments
(
    parent_id         bigint,
    id                bigserial,
    metadata_id       uuid    not null,
    version           int     not null,
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
    foreign key (metadata_id) references metadata (id),
    foreign key (profile_id) references profiles (id),
    foreign key (impersonator_id) references profiles (id),
    foreign key (parent_id) references metadata_comments (id)
);

alter table metadata
    add column comments_enabled boolean not null default false;
alter table metadata
    add column comment_replies_enabled boolean not null default false;

create index metadata_comment_metadata_ix on metadata_comments (metadata_id, version, visibility, status, created desc) where status != 'pending';

alter table profile_attribute_types
    add column protected boolean not null default false;

insert into profile_attribute_types (id, name, description, visibility, protected)
values ('bosca.profiles.comment.disabled', 'Comments Disabled', 'Comments Disabled', 'system'::profile_visibility, true);

insert into profile_attribute_types (id, name, description, visibility, protected)
values ('bosca.profiles.comment.moderator', 'Moderator', 'Moderator', 'public'::profile_visibility, true);
