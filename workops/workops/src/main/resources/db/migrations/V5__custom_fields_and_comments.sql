-- Work Ops — Phase 4 (specs/workops/plan.md §4)
--
-- Lands custom-field support (R5) and the task-comment surface (R6).
--
-- Comments shape mirrors the Rust metadata_comments / metadata_comment_likes
-- pattern (workspace/core/server/src/datastores/content/comments.rs in
-- the sowers-io/bosca rust repo) — same column set, same status enum
-- reuse, same likes denormalization. The only substitution is
-- task_id replacing the metadata_id + version pair.
--
-- The custom-field configuration scheme is workops-internal: each
-- (scheme, task_type, field_key) row declares required / hidden /
-- default behavior. The plan (R5) calls for core-forms-driven type
-- validation, but core-forms does not yet expose a per-field
-- validator API; this migration ships the scaffolding so the
-- workops side enforces required + hidden today, and the deeper
-- type-validation hook lands when core-forms gains a field-binding
-- model.

-- ---------------------------------------------------------------
-- Task comments (R6) — mirror of metadata_comments / metadata_comment_likes.
--
-- The migration defines workops-local copies of `profile_visibility`
-- and `comment_status` (instead of referencing the public-schema
-- variants V3 / V55 of CoreMigration create) so the workops migration
-- runs self-contained — the test fixtures and any future single-
-- schema deploy paths apply without needing CoreMigration as a
-- prerequisite. The enum labels match the public-schema variants
-- exactly so the application layer can map between the two without
-- a translation table.
-- ---------------------------------------------------------------

create type workops.profile_visibility as enum ('public', 'user', 'system', 'friends', 'friends_of_friends');
create type workops.comment_status as enum ('pending', 'blocked', 'pending_approval', 'approved');

create table workops.task_comment (
    parent_id           bigint,
    id                  bigserial primary key,
    task_id             uuid        not null references workops.task(id) on delete cascade,
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
    foreign key (parent_id) references workops.task_comment(id)
);

create index task_comment_task_idx on workops.task_comment(task_id, status, created desc) where status != 'pending' and deleted = false;
create index task_comment_parent_idx on workops.task_comment(parent_id) where parent_id is not null;

create table workops.task_comment_likes (
    comment_id  bigint not null,
    profile_id  uuid   not null,
    created     timestamptz not null default now(),
    primary key (comment_id, profile_id),
    foreign key (comment_id) references workops.task_comment(id) on delete cascade
);

-- ---------------------------------------------------------------
-- Custom-field configuration (R5).
--
-- A scheme contains many field configurations: each row declares
-- behavior (required / hidden / default / help) for a (task_type,
-- field_key) pair. A null task_type_id makes the row apply to every
-- task type within the scheme. Phase 7's MANAGE_FIELDS permission
-- gates admin CRUD; Phase 4 ships read + enforce.
-- ---------------------------------------------------------------

create table workops.task_field_configuration_scheme (
    id              uuid        not null default gen_random_uuid() primary key,
    name            varchar     not null unique,
    description     varchar,
    version         bigint      not null default 0
);

create table workops.task_field_configuration (
    id                          uuid        not null default gen_random_uuid() primary key,
    scheme_id                   uuid        not null references workops.task_field_configuration_scheme(id) on delete cascade,
    -- Null = applies to every task type within the scheme.
    task_type_id                uuid        references workops.task_type(id) on delete cascade,
    -- Stable identifier the task's customFieldValues map keys on. For
    -- Phase 4 the key is workops-internal; once core-forms gains a
    -- bound-field model, this column will FK into that model.
    field_key                   varchar     not null,
    required                    boolean     not null default false,
    hidden                      boolean     not null default false,
    -- JSON expression that yields the default value when the field
    -- is missing on createTask. Phase 4 supports literal JSON only;
    -- richer expressions land alongside the BQL parser (Phase 6).
    default_value_expression    jsonb,
    help_text                   varchar,
    constraint task_field_unique_within_scheme unique (scheme_id, task_type_id, field_key)
);

create index task_field_configuration_scheme_idx on workops.task_field_configuration(scheme_id);

-- The Project row already has default_task_type_scheme_id /
-- default_workflow_scheme_id columns from V2 / V3. Add the field-
-- configuration scheme reference now.
alter table workops.project add column if not exists default_field_configuration_scheme_id uuid;
alter table workops.project
    add constraint project_default_field_configuration_scheme_fk
    foreign key (default_field_configuration_scheme_id)
    references workops.task_field_configuration_scheme(id) on delete restrict;

-- Seed a "Default Field Configuration Scheme" so projects created
-- without an explicit scheme reference still have a row to anchor
-- enforcement. Phase 4 ships zero rows in `task_field_configuration`
-- — admins add fields as their projects need them — but the scheme
-- exists so future migrations and admin tooling have a stable id.
insert into workops.task_field_configuration_scheme (id, name, description) values
    ('80000000-0000-0000-0000-000000000001',
     'Default Field Configuration Scheme',
     'Built-in scheme that holds the per-task-type custom-field rules. Empty by default; admins extend per project.');

update workops.project
   set default_field_configuration_scheme_id = '80000000-0000-0000-0000-000000000001'
 where default_field_configuration_scheme_id is null;
