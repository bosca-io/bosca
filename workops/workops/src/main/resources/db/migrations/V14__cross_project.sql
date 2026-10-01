-- Work Ops — Phase 16 (specs/workops/plan.md §16, R26)
--
-- Cross-project primitives: program-scoped Release, SharedComponent,
-- and the task ↔ affected projects join. The Phase 2 task table
-- already carries `affects_version_ids`, `fix_version_ids`,
-- `component_ids`, `label_ids` arrays — those stay for BQL
-- ergonomics; the join table here is the canonical surface for
-- audit and cascade.

create table workops.release (
    id                  uuid    not null default gen_random_uuid() primary key,
    program_id          uuid    not null references workops.program(id) on delete cascade,
    name                varchar not null,
    description         varchar,
    release_date        timestamptz,
    released_at         timestamptz,
    owner_profile_id    uuid,
    version             bigint  not null default 0,
    unique (program_id, name)
);

create index release_program_idx on workops.release(program_id);
create index release_date_idx on workops.release(release_date) where release_date is not null;

create table workops.release_component_version (
    release_id   uuid not null references workops.release(id) on delete cascade,
    project_id   uuid not null references workops.project(id) on delete cascade,
    version_id   uuid not null references workops.version(id) on delete cascade,
    primary key (release_id, project_id, version_id)
);

create table workops.shared_component (
    id                              uuid    not null default gen_random_uuid() primary key,
    program_id                      uuid    not null references workops.program(id) on delete cascade,
    name                            varchar not null,
    description                     varchar,
    default_assignee_profile_id     uuid,
    version                         bigint  not null default 0,
    unique (program_id, name)
);

create table workops.shared_component_participation (
    shared_component_id uuid not null references workops.shared_component(id) on delete cascade,
    project_id          uuid not null references workops.project(id) on delete cascade,
    primary key (shared_component_id, project_id)
);

create table workops.task_affected_project (
    task_id     uuid not null references workops.task(id) on delete cascade,
    project_id  uuid not null references workops.project(id) on delete cascade,
    added_at    timestamptz not null default now(),
    primary key (task_id, project_id)
);

create index task_affected_project_project_idx on workops.task_affected_project(project_id);

-- Cross-project move audit trail: each move writes one row.
create table workops.task_move_audit (
    id                          uuid        not null default gen_random_uuid() primary key,
    task_id                     uuid        not null references workops.task(id) on delete cascade,
    from_project_id             uuid        not null,
    to_project_id               uuid        not null,
    legacy_key                  varchar(40) not null,
    new_key                     varchar(40) not null,
    moved_by_principal_id       uuid        not null,
    moved_at                    timestamptz not null default now(),
    status_mapping              jsonb       not null default '{}'::jsonb,
    field_mapping               jsonb       not null default '{}'::jsonb
);

create index task_move_audit_task_idx on workops.task_move_audit(task_id);
