-- ---------------------------------------------------------------
-- Requirement comments — follows spec_comment / task_comment pattern
-- ---------------------------------------------------------------

create table workops.requirement_comment (
    parent_id           bigint,
    id                  bigserial primary key,
    requirement_id      uuid        not null references workops.requirement(id) on delete cascade,
    profile_id          uuid        not null,
    impersonator_id     uuid,
    visibility          workops.profile_visibility default 'user',
    created             timestamptz not null default now(),
    modified            timestamptz not null default now(),
    status              workops.comment_status     default 'pending'::workops.comment_status,
    content             text        not null check (length(content) > 0),
    attributes          jsonb,
    system_attributes   jsonb,
    has_replies         boolean     not null default false,
    deleted             boolean     not null default false,
    likes               int         not null default 0,
    foreign key (parent_id) references workops.requirement_comment(id)
);

create index requirement_comment_requirement_idx on workops.requirement_comment(requirement_id, status, created desc) where status != 'pending' and deleted = false;
create index requirement_comment_parent_idx on workops.requirement_comment(parent_id) where parent_id is not null;

create table workops.requirement_comment_likes (
    comment_id  bigint not null,
    profile_id  uuid   not null,
    created     timestamptz not null default now(),
    primary key (comment_id, profile_id),
    foreign key (comment_id) references workops.requirement_comment(id) on delete cascade
);
