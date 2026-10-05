-- Work Ops — Phase 2 (specs/workops/plan.md §2)
--
-- Lands the Portfolio → Program → Project hierarchy (R1), the Task
-- core (R2), the type / status / priority / resolution lookup tables
-- (R3, R4, R9), and the append-only task history (R17). The workflow
-- engine (R4 transitions, conditions, validators, post-functions),
-- custom fields (R5), comments (R6), links (R7), sprints / boards
-- (R8), versions / components / labels (R9), and the rest of the
-- subsystem land in their own dedicated phases — this migration
-- ships only what Phase 2 promises and nothing further.

-- ---------------------------------------------------------------
-- Hierarchy: Portfolio → Program → Project
-- ---------------------------------------------------------------

create type workops.status_category as enum ('TODO', 'IN_PROGRESS', 'DONE', 'CANCELLED');
create type workops.task_hierarchy_level as enum ('SUBTASK', 'STANDARD', 'EPIC', 'INITIATIVE');

create table workops.portfolio (
    id                  uuid        not null default gen_random_uuid() primary key,
    -- 2–10 char uppercase code; service-layer validates the regex.
    key                 varchar(10) not null unique,
    name                varchar     not null,
    description         varchar,
    owner_profile_id    uuid        not null,
    archived_at         timestamptz,
    created_at          timestamptz not null default now(),
    modified_at         timestamptz not null default now(),
    -- Optimistic-locking version; bumped on every update.
    version             bigint      not null default 0
);

create table workops.program (
    id                  uuid        not null default gen_random_uuid() primary key,
    portfolio_id        uuid        not null references workops.portfolio(id) on delete restrict,
    -- Unique within portfolio, not globally.
    key                 varchar(10) not null,
    name                varchar     not null,
    description         varchar,
    owner_profile_id    uuid        not null,
    start_date          timestamptz,
    target_date         timestamptz,
    archived_at         timestamptz,
    created_at          timestamptz not null default now(),
    modified_at         timestamptz not null default now(),
    version             bigint      not null default 0,
    constraint program_key_unique_within_portfolio unique (portfolio_id, key)
);

create table workops.project (
    id                              uuid        not null default gen_random_uuid() primary key,
    program_id                      uuid        not null references workops.program(id) on delete restrict,
    -- Globally unique because it is the prefix of every task key.
    key                             varchar(10) not null unique,
    name                            varchar     not null,
    description                     varchar,
    owner_profile_id                uuid        not null,
    -- Phase 2 wires default_task_type_scheme_id to the seeded scheme;
    -- the other three remain nullable until their owning phases land
    -- (3, 7, 7 respectively). NOT NULL is tightened by later phases.
    default_task_type_scheme_id     uuid,
    default_workflow_scheme_id      uuid,
    default_permission_scheme_id    uuid,
    default_notification_scheme_id  uuid,
    archived_at                     timestamptz,
    created_at                      timestamptz not null default now(),
    modified_at                     timestamptz not null default now(),
    version                         bigint      not null default 0
);

-- Per-project task-key sequence. Stored as a regular row rather than
-- a Postgres SEQUENCE object (per Implementation Decisions §"Task
-- keys") so renames stay atomic with the counter and the value
-- survives schema dump / restore cycles cleanly.
create table workops.project_key_counter (
    project_id          uuid    not null primary key references workops.project(id) on delete restrict,
    -- The largest task-key sequence number ever minted for this
    -- project. Starts at 0 (no keys minted) and is bumped atomically
    -- by `update ... set last_used = last_used + 1 ... returning last_used`
    -- so two concurrent task creates cannot collide. Numbers are never
    -- reused — deleted task keys leave gaps; the counter only ever
    -- advances.
    last_used           bigint  not null default 0
);

-- Old-key redirect map. Phase 2 inserts no rows; Phase 16 (R30
-- moveTaskToProject) and project-rename flows write here so historical
-- task keys remain resolvable.
create table workops.task_key_alias (
    -- The legacy key as it appeared in tasks before the move / rename.
    legacy_key          varchar(40) not null primary key,
    task_id             uuid        not null,
    -- Soft delete: archives a redirect without losing history.
    archived_at         timestamptz
);

-- ---------------------------------------------------------------
-- Task type, scheme, status, priority, resolution lookup tables
-- ---------------------------------------------------------------

create table workops.task_type (
    id                  uuid        not null default gen_random_uuid() primary key,
    name                varchar     not null unique,
    description         varchar,
    icon_key            varchar     not null,
    color_hex           varchar(7)  not null,
    hierarchy_level     workops.task_hierarchy_level not null default 'STANDARD',
    version             bigint      not null default 0
);

create table workops.task_type_scheme (
    id                      uuid        not null default gen_random_uuid() primary key,
    name                    varchar     not null unique,
    description             varchar,
    -- Ordered list of task_type ids. Stored as a uuid[] array so the
    -- repository binds the field directly without a junction table.
    task_type_ids           uuid[]      not null,
    default_task_type_id    uuid        not null references workops.task_type(id) on delete restrict,
    version                 bigint      not null default 0
);

create table workops.status (
    id                  uuid        not null default gen_random_uuid() primary key,
    name                varchar     not null unique,
    description         varchar,
    category            workops.status_category not null,
    color_hex           varchar(7)  not null,
    version             bigint      not null default 0
);

create table workops.priority (
    id                  uuid        not null default gen_random_uuid() primary key,
    name                varchar     not null unique,
    description         varchar,
    icon_key            varchar     not null,
    color_hex           varchar(7)  not null,
    display_order       int         not null,
    version             bigint      not null default 0
);

create table workops.resolution (
    id                  uuid        not null default gen_random_uuid() primary key,
    name                varchar     not null unique,
    description         varchar,
    display_order       int         not null,
    version             bigint      not null default 0
);

-- ---------------------------------------------------------------
-- Task — the central work-item row.
-- ---------------------------------------------------------------

create table workops.task (
    id                          uuid        not null default gen_random_uuid() primary key,
    -- {PROJECT_KEY}-{N}; unique cluster-wide because the key is also
    -- a public handle. Service layer mints it inside the same
    -- transaction as the row insert so concurrent creates don't race.
    key                         varchar(40) not null unique,
    project_id                  uuid        not null references workops.project(id) on delete restrict,
    task_type_id                uuid        not null references workops.task_type(id) on delete restrict,
    status_id                   uuid        not null references workops.status(id) on delete restrict,
    priority_id                 uuid        not null references workops.priority(id) on delete restrict,
    summary                     varchar(255) not null,
    description_markdown        text,
    description_html            text,
    reporter_profile_id         uuid        not null,
    assignee_profile_id         uuid,
    -- Phase 2 ships the columns; Phase 3 (R7) enforces the parent /
    -- epic constraints via the workflow engine and link layer.
    parent_task_id              uuid        references workops.task(id) on delete restrict,
    epic_task_id                uuid        references workops.task(id) on delete restrict,
    sprint_id                   uuid,
    affects_version_ids         uuid[]      not null default '{}'::uuid[],
    fix_version_ids             uuid[]      not null default '{}'::uuid[],
    component_ids               uuid[]      not null default '{}'::uuid[],
    label_ids                   uuid[]      not null default '{}'::uuid[],
    original_estimate_seconds   bigint,
    remaining_estimate_seconds  bigint,
    time_spent_seconds          bigint      not null default 0,
    due_date                    timestamptz,
    start_date                  timestamptz,
    resolution_id               uuid        references workops.resolution(id) on delete restrict,
    resolution_at               timestamptz,
    sla_due_at                  timestamptz,
    content_item_id             uuid,
    collection_id               uuid,
    -- Phase 4 (R5) populates this through `core-forms`. Phase 2 ships
    -- it as `{}` and the repository never reads it.
    custom_field_values         jsonb       not null default '{}'::jsonb,
    watcher_profile_ids         uuid[]      not null default '{}'::uuid[],
    vote_count                  int         not null default 0,
    external_references         jsonb,
    created_at                  timestamptz not null default now(),
    modified_at                 timestamptz not null default now(),
    created_by_principal_id     uuid        not null,
    modified_by_principal_id    uuid        not null,
    -- Soft-delete tombstone (R2). Hard delete is a separate
    -- permission added in Phase 7.
    deleted_at                  timestamptz,
    version                     bigint      not null default 0
);

create index task_project_idx          on workops.task(project_id) where deleted_at is null;
create index task_status_idx           on workops.task(status_id)  where deleted_at is null;
create index task_assignee_idx         on workops.task(assignee_profile_id) where deleted_at is null;
create index task_modified_idx         on workops.task(modified_at desc);
create index task_parent_idx           on workops.task(parent_task_id) where parent_task_id is not null;
create index task_epic_idx             on workops.task(epic_task_id)   where epic_task_id   is not null;

-- ---------------------------------------------------------------
-- Append-only audit history (R17).
--
-- Partitioned monthly by `changed_at` per the Implementation Decisions
-- section: "monthly partitions for retention / archival ease". Phase 2
-- pre-creates 13 partitions (current month + 12 forward) so a year of
-- writes never blocks waiting for partition creation. The retention
-- job (Phase 10) prunes old partitions and creates new ones on a
-- rolling basis.
--
-- The application role's lack of UPDATE / DELETE on this table is the
-- non-negotiable from R17 ("History is append-only at the DB layer").
-- Phase 10 introduces the dedicated retention role and switches the
-- service role's grants to INSERT-only; Phase 2 ships the table with
-- the constraint that the writer never executes UPDATE / DELETE — a
-- claim the service-layer tests in this phase enforce by construction
-- (the repository exposes only an `add` operation).
-- ---------------------------------------------------------------

create table workops.task_history (
    id                          uuid        not null default gen_random_uuid(),
    task_id                     uuid        not null,
    changed_at                  timestamptz not null default now(),
    changed_by_principal_id     uuid        not null,
    changed_by_profile_id       uuid,
    -- One JSON array of FieldChange objects per logical change (R17:
    -- multi-field updates produce ONE history entry with multiple
    -- field changes). The shape is enforced at the service layer
    -- because the partitioned-table layer can't easily own a
    -- per-element check constraint.
    changes                     jsonb       not null,
    primary key (id, changed_at)
) partition by range (changed_at);

create index task_history_task_idx on workops.task_history(task_id);

-- Pre-create 13 monthly partitions (current + 12 forward).
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
        p_name  := 'task_history_' || to_char(p_month, 'YYYY_MM');
        execute format(
            'create table if not exists workops.%I partition of workops.task_history for values from (%L) to (%L)',
            p_name,
            p_month,
            p_next
        );
    end loop;
end $$;

-- ---------------------------------------------------------------
-- Seed data. R3, R4, and R9 list the canonical built-ins.
-- ---------------------------------------------------------------

-- Task types (R3 acceptance criteria). Sub-task lives at SUBTASK,
-- standard work at STANDARD, epics at EPIC, initiatives at INITIATIVE.
insert into workops.task_type (id, name, description, icon_key, color_hex, hierarchy_level)
values
    -- Use stable ids so the seed scheme below can reference them
    -- without a sub-select dance and the IDs are visible in tests.
    ('00000000-0000-0000-0000-000000000001', 'Bug',        'Defect in shipped functionality.',           'bug',        '#E5493A', 'STANDARD'),
    ('00000000-0000-0000-0000-000000000002', 'Story',      'User-visible deliverable expressed as a user story.', 'story', '#36B37E', 'STANDARD'),
    ('00000000-0000-0000-0000-000000000003', 'Task',       'Generic unit of work.',                       'task',       '#4BADE8', 'STANDARD'),
    ('00000000-0000-0000-0000-000000000004', 'Epic',       'Container for related stories spanning a deliverable.', 'epic', '#904EE2', 'EPIC'),
    ('00000000-0000-0000-0000-000000000005', 'Sub-task',   'Decomposition of a standard task.',           'subtask',    '#6B778C', 'SUBTASK'),
    ('00000000-0000-0000-0000-000000000006', 'Initiative', 'Cross-program rollup container for epics.',   'initiative', '#FF8B00', 'INITIATIVE');

-- Default task type scheme (R3): contains all six standard types,
-- defaulted to "Task". Project rows reference this id via
-- `default_task_type_scheme_id` until an admin replaces it.
insert into workops.task_type_scheme (id, name, description, task_type_ids, default_task_type_id)
values
    ('10000000-0000-0000-0000-000000000001',
     'Default Task Type Scheme',
     'Built-in scheme containing Bug, Story, Task, Epic, Sub-task, Initiative.',
     array[
        '00000000-0000-0000-0000-000000000001'::uuid,
        '00000000-0000-0000-0000-000000000002'::uuid,
        '00000000-0000-0000-0000-000000000003'::uuid,
        '00000000-0000-0000-0000-000000000004'::uuid,
        '00000000-0000-0000-0000-000000000005'::uuid,
        '00000000-0000-0000-0000-000000000006'::uuid
     ],
     '00000000-0000-0000-0000-000000000003'); -- Task

-- Statuses (R4). Phase 2 seeds the canonical lifecycle so tasks can be
-- created and updated without an admin defining a workflow first.
-- Phase 3 lets admins compose these (and admin-defined) statuses into
-- per-project workflows.
insert into workops.status (id, name, description, category, color_hex)
values
    ('20000000-0000-0000-0000-000000000001', 'To Do',       'Not yet started.',                                  'TODO',        '#6B778C'),
    ('20000000-0000-0000-0000-000000000002', 'In Progress', 'Actively being worked.',                            'IN_PROGRESS', '#4BADE8'),
    ('20000000-0000-0000-0000-000000000003', 'In Review',   'Awaiting review of completed work.',                'IN_PROGRESS', '#0052CC'),
    ('20000000-0000-0000-0000-000000000004', 'Done',        'Completed successfully.',                           'DONE',        '#36B37E'),
    ('20000000-0000-0000-0000-000000000005', 'Cancelled',   'Closed without completion (won''t fix, duplicate).', 'CANCELLED',   '#DE350B');

-- Priorities (R2 reads this set; canonical Lowest → Critical scale).
insert into workops.priority (id, name, description, icon_key, color_hex, display_order)
values
    ('30000000-0000-0000-0000-000000000001', 'Lowest',   'Trivial / nice-to-have.',           'priority-lowest',   '#0065FF', 0),
    ('30000000-0000-0000-0000-000000000002', 'Low',      'Below normal escalation.',          'priority-low',      '#36B37E', 1),
    ('30000000-0000-0000-0000-000000000003', 'Medium',   'Default priority.',                 'priority-medium',   '#FFAB00', 2),
    ('30000000-0000-0000-0000-000000000004', 'High',     'Above normal escalation.',          'priority-high',     '#FF8B00', 3),
    ('30000000-0000-0000-0000-000000000005', 'Highest',  'Top of the standard scale.',        'priority-highest',  '#FF5630', 4),
    ('30000000-0000-0000-0000-000000000006', 'Critical', 'Drop everything; production-affecting.', 'priority-critical', '#DE350B', 5);

-- Resolutions (R9 explicit list).
insert into workops.resolution (id, name, description, display_order)
values
    ('40000000-0000-0000-0000-000000000001', 'Done',             'The work was completed as described.',                  0),
    ('40000000-0000-0000-0000-000000000002', 'Fixed',             'A defect was repaired.',                                1),
    ('40000000-0000-0000-0000-000000000003', 'Won''t Fix',        'The team has decided not to address this work.',        2),
    ('40000000-0000-0000-0000-000000000004', 'Duplicate',         'Another task already tracks this work; close as dup.',  3),
    ('40000000-0000-0000-0000-000000000005', 'Cannot Reproduce',  'The reported behavior cannot be observed.',             4),
    ('40000000-0000-0000-0000-000000000006', 'Incomplete',        'Insufficient information to act; reopen if more comes.', 5),
    ('40000000-0000-0000-0000-000000000007', 'Declined',          'Request out of scope or not aligned with direction.',   6);
