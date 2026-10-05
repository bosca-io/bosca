-- Work Ops — Phase 3 (specs/workops/plan.md §3)
--
-- Adds the workflow engine and the task-link graph (R4, R7). The
-- transition rules — Conditions, Validators, PostFunctions — are
-- stored as JSONB so the sealed Kotlin hierarchies in `core-workops`
-- round-trip without per-variant columns. Cycle detection on
-- `BLOCKS`-category links runs in the service layer; the database
-- only enforces "no self-links" and "(source, target, type) is
-- unique".

-- Lowercase labels match Bosca's EnumMapper.bind convention
-- (`value.name.lowercase()`); the Kotlin enum keeps SCREAMING_CASE.
create type workops.link_category as enum ('blocks', 'duplicates', 'relates_to', 'clones', 'causes', 'custom');

-- ---------------------------------------------------------------
-- Workflow definitions
-- ---------------------------------------------------------------

create table workops.workflow (
    id                  uuid        not null default gen_random_uuid() primary key,
    name                varchar     not null unique,
    description         varchar,
    -- Stable handle for the state new tasks land in. Nullable until
    -- at least one workflow_state row is inserted; the service layer
    -- enforces that the workflow is fully defined before it can be
    -- referenced from a workflow_scheme.
    initial_state_id    uuid,
    version             bigint      not null default 0
);

create table workops.workflow_state (
    id                  uuid        not null default gen_random_uuid() primary key,
    workflow_id         uuid        not null references workops.workflow(id) on delete cascade,
    status_id           uuid        not null references workops.status(id) on delete restrict,
    display_order       int         not null,
    -- Phase 8 SLA wiring.
    sla_policy_id       uuid,
    constraint workflow_state_status_unique unique (workflow_id, status_id)
);

alter table workops.workflow
    add constraint workflow_initial_state_fk
    foreign key (initial_state_id) references workops.workflow_state(id) deferrable initially deferred;

create table workops.workflow_transition (
    id                  uuid        not null default gen_random_uuid() primary key,
    workflow_id         uuid        not null references workops.workflow(id) on delete cascade,
    name                varchar     not null,
    description         varchar,
    -- text[] so the wildcard '*' literal and uuid strings share a slot.
    from_state_ids      text[]      not null,
    to_state_id         uuid        not null references workops.workflow_state(id) on delete cascade,
    -- JSONB columns for the sealed Condition / Validator / PostFunction
    -- hierarchies. The kotlinx-serialization @SerialName discriminator
    -- on each sealed root is what lets the workflow engine round-trip
    -- without a per-variant column dance.
    conditions          jsonb       not null default '[]'::jsonb,
    validators          jsonb       not null default '[]'::jsonb,
    post_functions      jsonb       not null default '[]'::jsonb,
    -- Phase 4 wires screens.
    screen_id           uuid
);

create index workflow_transition_to_state_idx on workops.workflow_transition(to_state_id);

create table workops.workflow_scheme (
    id                          uuid        not null default gen_random_uuid() primary key,
    name                        varchar     not null unique,
    description                 varchar,
    default_workflow_id         uuid        not null references workops.workflow(id) on delete restrict,
    -- Map of taskTypeId -> workflowId. Stored as jsonb so a single
    -- column carries the full per-type override map without a
    -- junction table; the repo decodes it as Map<UUID, UUID>.
    per_task_type_workflow_ids  jsonb       not null default '{}'::jsonb,
    version                     bigint      not null default 0
);

-- Tighten Phase 2's nullable scheme reference now that the seed is
-- about to land. Existing rows will be backfilled by the seed below
-- before the constraint is checked.
alter table workops.project add column if not exists default_workflow_scheme_id uuid;
-- (the column already exists from V2; the IF NOT EXISTS keeps the
-- migration idempotent against a partial run without redeclaring
-- the FK).

-- ---------------------------------------------------------------
-- Task links
-- ---------------------------------------------------------------

create table workops.task_link_type (
    id                  uuid        not null default gen_random_uuid() primary key,
    name                varchar     not null unique,
    inward_label        varchar     not null,
    outward_label       varchar     not null,
    category            workops.link_category not null,
    version             bigint      not null default 0
);

create table workops.task_link (
    id                          uuid        not null default gen_random_uuid() primary key,
    link_type_id                uuid        not null references workops.task_link_type(id) on delete restrict,
    source_task_id              uuid        not null references workops.task(id) on delete cascade,
    target_task_id              uuid        not null references workops.task(id) on delete cascade,
    created_at                  timestamptz not null default now(),
    created_by_principal_id     uuid        not null,
    constraint task_link_no_self_loop check (source_task_id <> target_task_id),
    constraint task_link_unique_triple unique (source_task_id, target_task_id, link_type_id)
);

create index task_link_source_idx on workops.task_link(source_task_id);
create index task_link_target_idx on workops.task_link(target_task_id);

-- ---------------------------------------------------------------
-- Seed default workflow + scheme + link types.
-- ---------------------------------------------------------------

-- Workflow row first (initial_state_id is filled in below).
insert into workops.workflow (id, name, description) values
    ('50000000-0000-0000-0000-000000000001', 'Default', 'Built-in three-state workflow: To Do -> In Progress -> Done with a wildcard Reopen.');

-- Three states pinned to seeded statuses.
insert into workops.workflow_state (id, workflow_id, status_id, display_order) values
    ('51000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',  0), -- To Do
    ('51000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 10), -- In Progress
    ('51000000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000004', 20); -- Done

update workops.workflow
   set initial_state_id = '51000000-0000-0000-0000-000000000001'
 where id = '50000000-0000-0000-0000-000000000001';

-- Default transitions. JSON arrays use the @SerialName discriminator
-- the sealed hierarchies declare in core-workops.
insert into workops.workflow_transition
    (id, workflow_id, name, from_state_ids, to_state_id, conditions, validators, post_functions)
values
    ('52000000-0000-0000-0000-000000000001',
     '50000000-0000-0000-0000-000000000001',
     'Start work',
     array['51000000-0000-0000-0000-000000000001'],
     '51000000-0000-0000-0000-000000000002',
     '[]'::jsonb, '[]'::jsonb, '[]'::jsonb),
    ('52000000-0000-0000-0000-000000000002',
     '50000000-0000-0000-0000-000000000001',
     'Resolve',
     array['51000000-0000-0000-0000-000000000002'],
     '51000000-0000-0000-0000-000000000003',
     '[]'::jsonb,
     '[]'::jsonb,
     '[]'::jsonb),
    ('52000000-0000-0000-0000-000000000003',
     '50000000-0000-0000-0000-000000000001',
     'Reopen',
     array['*'],
     '51000000-0000-0000-0000-000000000001',
     '[]'::jsonb, '[]'::jsonb, '[]'::jsonb);

-- Default workflow scheme (every project picks this up at create
-- time via ProjectService.create).
insert into workops.workflow_scheme (id, name, description, default_workflow_id)
values
    ('60000000-0000-0000-0000-000000000001',
     'Default Workflow Scheme',
     'Built-in scheme that points every task type at the Default workflow.',
     '50000000-0000-0000-0000-000000000001');

-- Backfill any existing project rows with the default scheme so
-- transitions work immediately. Phase 2's projects had a null
-- default_workflow_scheme_id.
update workops.project
   set default_workflow_scheme_id = '60000000-0000-0000-0000-000000000001'
 where default_workflow_scheme_id is null;

alter table workops.project
    add constraint project_default_workflow_scheme_fk
    foreign key (default_workflow_scheme_id) references workops.workflow_scheme(id) on delete restrict;

-- Seed link types (R7). Enum literals are lowercase to match the
-- type definition above and Bosca's EnumMapper.bind convention.
insert into workops.task_link_type (id, name, inward_label, outward_label, category) values
    ('70000000-0000-0000-0000-000000000001', 'Blocks',     'is blocked by',  'blocks',         'blocks'),
    ('70000000-0000-0000-0000-000000000002', 'Duplicates', 'is duplicated by','duplicates',    'duplicates'),
    ('70000000-0000-0000-0000-000000000003', 'Relates',    'relates to',     'relates to',     'relates_to'),
    ('70000000-0000-0000-0000-000000000004', 'Clones',     'is cloned by',   'clones',         'clones'),
    ('70000000-0000-0000-0000-000000000005', 'Causes',     'is caused by',   'causes',         'causes');
