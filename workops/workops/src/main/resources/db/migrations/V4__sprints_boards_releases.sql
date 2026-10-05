-- Work Ops — Phase 5 (specs/workops/plan.md §5)
--
-- Lands the agile / release surface: sprints (R8), boards + columns
-- (R8), versions / components / labels / milestones (R9). The
-- task table's `sprint_id`, `affects_version_ids`, `fix_version_ids`,
-- `component_ids`, `label_ids` columns already exist from V2; this
-- migration populates the lookup tables they reference and adds the
-- `task.milestone_id` column.

create type workops.sprint_state as enum ('future', 'active', 'closed');
create type workops.board_type as enum ('kanban', 'scrum');
create type workops.swimlane_strategy as enum ('none', 'assignee', 'epic', 'priority', 'project', 'query');
create type workops.component_assignee_mode as enum (
    'unassigned', 'component_lead', 'project_default', 'component_lead_or_project_default'
);
create type workops.label_scope as enum ('global', 'portfolio', 'program', 'project');
create type workops.milestone_state as enum ('open', 'closed');

-- ---------------------------------------------------------------
-- Boards (R8). A board may sit at project or program scope; the
-- check constraint enforces exactly one parent reference.
-- ---------------------------------------------------------------

create table workops.board (
    id                  uuid        not null default gen_random_uuid() primary key,
    project_id          uuid        references workops.project(id) on delete cascade,
    program_id          uuid        references workops.program(id) on delete cascade,
    name                varchar     not null,
    type                workops.board_type not null,
    -- Phase 6 SavedFilter id; non-FK in Phase 5 because SavedFilter
    -- lands in Phase 6 with the BQL parser.
    filter_id           uuid,
    sub_filter_id       uuid,
    swimlane_strategy   workops.swimlane_strategy not null default 'none',
    created_at          timestamptz not null default now(),
    modified_at         timestamptz not null default now(),
    version             bigint      not null default 0,
    constraint board_exactly_one_parent check (
        (project_id is not null and program_id is null)
        or (project_id is null and program_id is not null)
    )
);

create index board_project_idx on workops.board(project_id) where project_id is not null;
create index board_program_idx on workops.board(program_id) where program_id is not null;

create table workops.board_column (
    id                  uuid        not null default gen_random_uuid() primary key,
    board_id            uuid        not null references workops.board(id) on delete cascade,
    name                varchar     not null,
    display_order       int         not null,
    -- Multi-status columns are common in kanban: "In Progress" might
    -- include both "In Progress" and "In Review" statuses.
    status_ids          uuid[]      not null,
    wip_limit           int,
    constraint board_column_unique_order unique (board_id, display_order)
);

create index board_column_board_idx on workops.board_column(board_id);

-- ---------------------------------------------------------------
-- Sprints (R8). At most one ACTIVE sprint per board — enforced
-- via a partial unique index on `(board_id) where state = 'active'`.
-- ---------------------------------------------------------------

create table workops.sprint (
    id                              uuid        not null default gen_random_uuid() primary key,
    board_id                        uuid        not null references workops.board(id) on delete cascade,
    name                            varchar     not null,
    goal                            varchar,
    state                           workops.sprint_state not null default 'future',
    start_date                      timestamptz,
    end_date                        timestamptz,
    complete_date                   timestamptz,
    committed_task_ids              uuid[]      not null default '{}'::uuid[],
    added_during_sprint_task_ids    uuid[]      not null default '{}'::uuid[],
    velocity_points                 double precision,
    created_at                      timestamptz not null default now(),
    modified_at                     timestamptz not null default now(),
    version                         bigint      not null default 0
);

create index sprint_board_idx on workops.sprint(board_id);
create unique index sprint_active_singleton on workops.sprint(board_id) where state = 'active';

-- ---------------------------------------------------------------
-- Versions, components, labels, milestones (R9).
-- ---------------------------------------------------------------

create table workops.version (
    id                  uuid        not null default gen_random_uuid() primary key,
    project_id          uuid        not null references workops.project(id) on delete restrict,
    name                varchar     not null,
    description         varchar,
    start_date          timestamptz,
    release_date        timestamptz,
    released            boolean     not null default false,
    archived            boolean     not null default false,
    sequence_number     int         not null,
    version             bigint      not null default 0,
    constraint version_unique_within_project unique (project_id, name)
);

create index version_project_idx on workops.version(project_id);

create table workops.component (
    id                              uuid        not null default gen_random_uuid() primary key,
    project_id                      uuid        not null references workops.project(id) on delete restrict,
    name                            varchar     not null,
    description                     varchar,
    default_assignee_profile_id     uuid,
    lead_profile_id                 uuid,
    assignee_mode                   workops.component_assignee_mode not null default 'unassigned',
    version                         bigint      not null default 0,
    constraint component_unique_within_project unique (project_id, name)
);

create index component_project_idx on workops.component(project_id);

create table workops.label (
    id                  uuid        not null default gen_random_uuid() primary key,
    name                varchar     not null,
    color_hex           varchar(7),
    scope               workops.label_scope not null default 'global',
    portfolio_id        uuid        references workops.portfolio(id) on delete cascade,
    program_id          uuid        references workops.program(id) on delete cascade,
    project_id          uuid        references workops.project(id) on delete cascade,
    version             bigint      not null default 0,
    -- Names are scoped: GLOBAL labels share the global namespace, PORTFOLIO labels
    -- the portfolio namespace, etc. Enforced by partial unique indexes below.
    constraint label_scope_parent_consistency check (
        (scope = 'global' and portfolio_id is null and program_id is null and project_id is null)
        or (scope = 'portfolio' and portfolio_id is not null and program_id is null and project_id is null)
        or (scope = 'program' and program_id is not null and portfolio_id is null and project_id is null)
        or (scope = 'project' and project_id is not null and portfolio_id is null and program_id is null)
    )
);

create unique index label_unique_global   on workops.label(name)               where scope = 'global';
create unique index label_unique_portfolio on workops.label(portfolio_id, name) where scope = 'portfolio';
create unique index label_unique_program  on workops.label(program_id, name)   where scope = 'program';
create unique index label_unique_project  on workops.label(project_id, name)   where scope = 'project';

create table workops.milestone (
    id                  uuid        not null default gen_random_uuid() primary key,
    program_id          uuid        not null references workops.program(id) on delete restrict,
    name                varchar     not null,
    description         varchar,
    target_date         timestamptz,
    state               workops.milestone_state not null default 'open',
    closed_at           timestamptz,
    version             bigint      not null default 0,
    constraint milestone_unique_within_program unique (program_id, name)
);

create index milestone_program_idx on workops.milestone(program_id);

-- Add task.milestone_id column for R9 task-to-milestone attachment.
alter table workops.task add column if not exists milestone_id uuid;
alter table workops.task
    add constraint task_milestone_fk
    foreign key (milestone_id) references workops.milestone(id) on delete restrict;
create index task_milestone_idx on workops.task(milestone_id) where milestone_id is not null;
