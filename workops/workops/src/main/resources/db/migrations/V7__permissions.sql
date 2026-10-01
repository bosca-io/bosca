-- Work Ops — Phase 7.A (specs/workops/plan.md §7.1)
--
-- Lands the permission-scheme + project-role surface from R11.
-- Notifications / watchers / digest jobs come in a follow-up
-- (V8 / Phase 7.B).
--
-- The scheme stores its grant map as a single jsonb column rather
-- than a junction table because it's admin-driven and rarely
-- mutates; the evaluator reads the entire row, decodes the
-- discriminated-union grants, and answers gates from memory.

create table workops.permission_scheme (
    id              uuid        not null default gen_random_uuid() primary key,
    name            varchar     not null unique,
    description     varchar,
    -- The grants jsonb shape:
    --   { "BROWSE_TASKS": [{ "type": "LoggedIn" }, …],
    --     "EDIT_TASKS":   [{ "type": "Assignee" }, { "type": "ProjectRole", "roleId": "<uuid>" }],
    --     … }
    -- The discriminator follows the `@SerialName` on the
    -- core-workops PermissionGrant sealed hierarchy.
    grants          jsonb       not null default '{}'::jsonb,
    version         bigint      not null default 0
);

create table workops.project_role (
    id              uuid        not null default gen_random_uuid() primary key,
    name            varchar     not null unique,
    description     varchar,
    version         bigint      not null default 0
);

create table workops.project_role_membership (
    project_id      uuid        not null references workops.project(id) on delete cascade,
    role_id         uuid        not null references workops.project_role(id) on delete cascade,
    profile_id      uuid        not null,
    primary key (project_id, role_id, profile_id)
);

create index project_role_membership_profile_idx on workops.project_role_membership(profile_id);
create index project_role_membership_project_idx on workops.project_role_membership(project_id);

-- Seed the canonical roles (R11). The names match the spec's
-- "Developer / Reviewer / Product Owner" suggestion plus a generic
-- "Member" so the seeded permission scheme has a non-empty audience
-- on every project.
insert into workops.project_role (id, name, description) values
    ('a0000000-0000-0000-0000-000000000001', 'Developer',     'Engineering contributor — can transition tasks and log work.'),
    ('a0000000-0000-0000-0000-000000000002', 'Reviewer',      'Reviews pull requests and resolves tasks.'),
    ('a0000000-0000-0000-0000-000000000003', 'Product Owner', 'Owns prioritization, sprint composition, and roadmap.'),
    ('a0000000-0000-0000-0000-000000000004', 'Member',        'Default project member with read access.');

-- Backfill existing project rows with the seeded permission scheme
-- (id 90000000-…). Phase 7's evaluator falls back to the seeded
-- scheme when a project's `default_permission_scheme_id` is null,
-- but populating the column makes the audit trail explicit.
insert into workops.permission_scheme (id, name, description, grants) values (
    '90000000-0000-0000-0000-000000000001',
    'Default Permission Scheme',
    'Built-in permission scheme. LoggedIn-readable; reporter / assignee / project-lead drive write paths; Member role for everyday contribution.',
    $$ {
        "BROWSE_TASKS":            [ {"type": "LoggedIn"} ],
        "VIEW_VOTERS_AND_WATCHERS":[ {"type": "LoggedIn"} ],
        "ASSIGNABLE_USER":         [ {"type": "LoggedIn"} ],
        "CREATE_TASKS":            [ {"type": "LoggedIn"} ],
        "ADD_COMMENTS":            [ {"type": "LoggedIn"} ],
        "WORK_ON_TASKS":           [ {"type": "LoggedIn"} ],
        "EDIT_OWN_COMMENTS":       [ {"type": "LoggedIn"} ],
        "DELETE_OWN_COMMENTS":     [ {"type": "LoggedIn"} ],
        "EDIT_OWN_WORKLOG":        [ {"type": "LoggedIn"} ],
        "MANAGE_WATCHERS":         [ {"type": "LoggedIn"} ],
        "EDIT_TASKS":              [ {"type": "Assignee"}, {"type": "Reporter"}, {"type": "ProjectLead"} ],
        "ASSIGN_TASKS":            [ {"type": "Assignee"}, {"type": "Reporter"}, {"type": "ProjectLead"} ],
        "RESOLVE_TASKS":           [ {"type": "Assignee"}, {"type": "ProjectLead"} ],
        "CLOSE_TASKS":             [ {"type": "Assignee"}, {"type": "ProjectLead"} ],
        "MOVE_TASKS":              [ {"type": "ProjectLead"} ],
        "LINK_TASKS":              [ {"type": "Assignee"}, {"type": "Reporter"}, {"type": "ProjectLead"} ],
        "SCHEDULE_TASKS":          [ {"type": "Assignee"}, {"type": "ProjectLead"} ],
        "DELETE_TASKS":            [ {"type": "ProjectLead"} ],
        "CREATE_ATTACHMENTS":      [ {"type": "LoggedIn"} ],
        "DELETE_OWN_ATTACHMENTS":  [ {"type": "LoggedIn"} ],
        "MANAGE_SPRINTS":          [ {"type": "ProjectLead"} ],
        "ADMINISTER_PROJECT":      [ {"type": "ProjectLead"} ],
        "MANAGE_WORKFLOWS":        [ {"type": "ProjectLead"} ],
        "MANAGE_FIELDS":           [ {"type": "ProjectLead"} ],
        "MANAGE_TASK_TYPES":       [ {"type": "ProjectLead"} ],
        "SET_TASK_SECURITY":       [ {"type": "ProjectLead"} ],
        "EDIT_ALL_COMMENTS":       [ {"type": "ProjectLead"} ],
        "DELETE_ALL_COMMENTS":     [ {"type": "ProjectLead"} ],
        "EDIT_ALL_WORKLOG":        [ {"type": "ProjectLead"} ],
        "DELETE_ALL_ATTACHMENTS":  [ {"type": "ProjectLead"} ],
        "MANAGE_ATTACHMENTS":      [ {"type": "ProjectLead"} ],
        "MANAGE_PROGRAM":          [ {"type": "ProjectLead"} ],
        "MANAGE_PORTFOLIO":        [ {"type": "ProjectLead"} ]
    } $$::jsonb
);

-- Phase 2 left default_permission_scheme_id nullable; Phase 7
-- backfills every existing project row with the seed.
update workops.project
   set default_permission_scheme_id = '90000000-0000-0000-0000-000000000001'
 where default_permission_scheme_id is null;

alter table workops.project
    add constraint project_default_permission_scheme_fk
    foreign key (default_permission_scheme_id)
    references workops.permission_scheme(id) on delete restrict;
