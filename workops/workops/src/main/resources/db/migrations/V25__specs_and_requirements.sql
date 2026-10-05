-- Work Ops — Spec-based development
--
-- Introduces Spec, Requirement, SpecContext, SpecTaskGeneration,
-- SpecComment, and append-only audit history for both Specs and
-- Requirements. Specs are the top-level unit for AI-generated or
-- manually authored specifications; Requirements attach to either
-- a Spec or a Task (epic). Both delegate rich content storage to
-- the content system via metadataId — WorkOps owns the project-
-- management semantics (workflow, ownership, context, task generation).

-- ---------------------------------------------------------------
-- Enum types
-- ---------------------------------------------------------------

create type workops.spec_version_source as enum ('workops', 'git_sync');
create type workops.generation_source as enum ('claude_code', 'manual', 'kit');
create type workops.spec_context_type as enum (
    'git_resource', 'metadata', 'collection', 'profile',
    'chat_channel', 'ai_session', 'spec', 'task', 'project',
    'external_uri'
);
create type workops.requirement_parent as enum ('spec', 'task');

-- ---------------------------------------------------------------
-- Spec key counter — per-project auto-incrementing key sequence,
-- following the same pattern as the task key counter.
-- ---------------------------------------------------------------

create table workops.spec_key_counter (
    project_id      uuid    not null primary key,
    next_number     bigint  not null default 1,
    constraint spec_key_counter_positive check (next_number > 0)
);

-- ---------------------------------------------------------------
-- Spec
-- ---------------------------------------------------------------

create table workops.spec (
    id                          uuid        not null default gen_random_uuid() primary key,
    key                         varchar     not null unique,
    metadata_id                 uuid        not null,
    program_id                  uuid        references workops.program(id) on delete restrict,
    project_id                  uuid        references workops.project(id) on delete restrict,
    status_id                   uuid        not null references workops.status(id) on delete restrict,
    workflow_id                 uuid        not null references workops.workflow(id) on delete restrict,
    owner_profile_id            uuid        not null,
    git_repository_id           uuid,
    git_path                    varchar,
    watcher_profile_ids         uuid[]      not null default '{}',
    label_ids                   uuid[]      not null default '{}',
    external_references         jsonb,
    public                      boolean     not null default false,
    public_content              boolean     not null default false,
    public_list                 boolean     not null default false,
    public_supplementary        boolean     not null default false,
    created_at                  timestamptz not null default now(),
    modified_at                 timestamptz not null default now(),
    created_by_principal_id     uuid        not null,
    modified_by_principal_id    uuid        not null,
    deleted_at                  timestamptz,
    version                     bigint      not null default 0
);

create index spec_project_idx on workops.spec(project_id) where deleted_at is null;
create index spec_program_idx on workops.spec(program_id) where deleted_at is null;
create index spec_status_idx on workops.spec(status_id) where deleted_at is null;
create index spec_owner_idx on workops.spec(owner_profile_id) where deleted_at is null;
create index spec_metadata_idx on workops.spec(metadata_id);
create index spec_modified_idx on workops.spec(modified_at desc);

-- ---------------------------------------------------------------
-- Requirement key counter
-- ---------------------------------------------------------------

create table workops.requirement_key_counter (
    project_id      uuid    not null primary key,
    next_number     bigint  not null default 1,
    constraint requirement_key_counter_positive check (next_number > 0)
);

-- ---------------------------------------------------------------
-- Requirement
-- ---------------------------------------------------------------

create table workops.requirement (
    id                          uuid        not null default gen_random_uuid() primary key,
    key                         varchar     not null unique,
    metadata_id                 uuid        not null,
    parent_type                 workops.requirement_parent not null,
    parent_id                   uuid        not null,
    status_id                   uuid        not null references workops.status(id) on delete restrict,
    workflow_id                 uuid        not null references workops.workflow(id) on delete restrict,
    priority_id                 uuid        not null references workops.priority(id) on delete restrict,
    assignee_profile_id         uuid,
    sort_order                  int         not null default 0,
    label_ids                   uuid[]      not null default '{}',
    external_references         jsonb,
    public                      boolean     not null default false,
    public_content              boolean     not null default false,
    public_list                 boolean     not null default false,
    public_supplementary        boolean     not null default false,
    created_at                  timestamptz not null default now(),
    modified_at                 timestamptz not null default now(),
    created_by_principal_id     uuid        not null,
    modified_by_principal_id    uuid        not null,
    deleted_at                  timestamptz,
    version                     bigint      not null default 0
);

create index requirement_parent_idx on workops.requirement(parent_type, parent_id) where deleted_at is null;
create index requirement_status_idx on workops.requirement(status_id) where deleted_at is null;
create index requirement_metadata_idx on workops.requirement(metadata_id);
create index requirement_modified_idx on workops.requirement(modified_at desc);

-- ---------------------------------------------------------------
-- Spec context — curated contextual references
-- ---------------------------------------------------------------

create table workops.spec_context (
    id                  uuid        not null default gen_random_uuid() primary key,
    spec_id             uuid        not null references workops.spec(id) on delete cascade,
    context_type        workops.spec_context_type not null,
    target_id           varchar     not null,
    label               varchar,
    attributes          jsonb,
    added_by_profile_id uuid        not null,
    created_at          timestamptz not null default now()
);

create index spec_context_spec_idx on workops.spec_context(spec_id);
create index spec_context_type_target_idx on workops.spec_context(context_type, target_id);

-- ---------------------------------------------------------------
-- Spec task generation — records of task creation events
-- ---------------------------------------------------------------

create table workops.spec_task_generation (
    id                          uuid        not null default gen_random_uuid() primary key,
    spec_id                     uuid        not null references workops.spec(id) on delete cascade,
    metadata_version            int         not null,
    source                      workops.generation_source not null,
    agent_session_id            uuid,
    generated_task_ids          uuid[]      not null default '{}',
    created_at                  timestamptz not null default now(),
    created_by_principal_id     uuid        not null
);

create index spec_task_generation_spec_idx on workops.spec_task_generation(spec_id);

-- ---------------------------------------------------------------
-- Spec comments — follows task_comment pattern (option A)
-- ---------------------------------------------------------------

create table workops.spec_comment (
    parent_id           bigint,
    id                  bigserial primary key,
    spec_id             uuid        not null references workops.spec(id) on delete cascade,
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
    foreign key (parent_id) references workops.spec_comment(id)
);

create index spec_comment_spec_idx on workops.spec_comment(spec_id, status, created desc) where status != 'pending' and deleted = false;
create index spec_comment_parent_idx on workops.spec_comment(parent_id) where parent_id is not null;

create table workops.spec_comment_likes (
    comment_id  bigint not null,
    profile_id  uuid   not null,
    created     timestamptz not null default now(),
    primary key (comment_id, profile_id),
    foreign key (comment_id) references workops.spec_comment(id) on delete cascade
);

-- ---------------------------------------------------------------
-- Spec history — append-only audit log, partitioned monthly
-- ---------------------------------------------------------------

create table workops.spec_history (
    id                          uuid        not null default gen_random_uuid(),
    spec_id                     uuid        not null,
    changed_at                  timestamptz not null default now(),
    changed_by_principal_id     uuid        not null,
    changed_by_profile_id       uuid,
    changes                     jsonb       not null,
    primary key (id, changed_at)
) partition by range (changed_at);

create index spec_history_spec_idx on workops.spec_history(spec_id);

do $$
declare
    start_month date;
    p_month     date;
    p_next      date;
    p_name      text;
    i           int;
begin
    start_month := date_trunc('month', now())::date;
    for i in 0..12 loop
        p_month := (start_month + (i || ' months')::interval)::date;
        p_next  := (p_month + interval '1 month')::date;
        p_name  := 'spec_history_' || to_char(p_month, 'YYYY_MM');
        execute format(
            'create table if not exists workops.%I partition of workops.spec_history for values from (%L) to (%L)',
            p_name,
            p_month,
            p_next
        );
    end loop;
end $$;

-- ---------------------------------------------------------------
-- Requirement history — append-only audit log, partitioned monthly
-- ---------------------------------------------------------------

create table workops.requirement_history (
    id                          uuid        not null default gen_random_uuid(),
    requirement_id              uuid        not null,
    changed_at                  timestamptz not null default now(),
    changed_by_principal_id     uuid        not null,
    changed_by_profile_id       uuid,
    changes                     jsonb       not null,
    primary key (id, changed_at)
) partition by range (changed_at);

create index requirement_history_requirement_idx on workops.requirement_history(requirement_id);

do $$
declare
    start_month date;
    p_month     date;
    p_next      date;
    p_name      text;
    i           int;
begin
    start_month := date_trunc('month', now())::date;
    for i in 0..12 loop
        p_month := (start_month + (i || ' months')::interval)::date;
        p_next  := (p_month + interval '1 month')::date;
        p_name  := 'requirement_history_' || to_char(p_month, 'YYYY_MM');
        execute format(
            'create table if not exists workops.%I partition of workops.requirement_history for values from (%L) to (%L)',
            p_name,
            p_month,
            p_next
        );
    end loop;
end $$;
