-- Work Ops — V21: Replace permission-scheme system with standard
-- Bosca entity-permission model (PermissibleEntity + EntityPermission).
--
-- This migration:
--   1. Adds PermissibleEntity columns to portfolio, program, project, task.
--   2. Creates per-entity permission tables following the metadata/collection pattern.
--   3. Drops the old permission_scheme, project_role, and project_role_membership tables.

-- 1. PermissibleEntity columns ------------------------------------------------

alter table workops.portfolio
    add column if not exists public               boolean not null default false,
    add column if not exists public_content        boolean not null default false,
    add column if not exists public_list           boolean not null default false,
    add column if not exists public_supplementary  boolean not null default false;

alter table workops.program
    add column if not exists public               boolean not null default false,
    add column if not exists public_content        boolean not null default false,
    add column if not exists public_list           boolean not null default false,
    add column if not exists public_supplementary  boolean not null default false;

alter table workops.project
    add column if not exists public               boolean not null default false,
    add column if not exists public_content        boolean not null default false,
    add column if not exists public_list           boolean not null default false,
    add column if not exists public_supplementary  boolean not null default false;

alter table workops.task
    add column if not exists public               boolean not null default false,
    add column if not exists public_content        boolean not null default false,
    add column if not exists public_list           boolean not null default false,
    add column if not exists public_supplementary  boolean not null default false;

-- 2. Per-entity permission tables ---------------------------------------------

create table workops.portfolio_permissions (
    portfolio_id  uuid              not null references workops.portfolio(id) on delete cascade,
    group_id      uuid              not null,
    action        permission_action not null,
    primary key (portfolio_id, group_id, action)
);

create table workops.program_permissions (
    program_id  uuid              not null references workops.program(id) on delete cascade,
    group_id    uuid              not null,
    action      permission_action not null,
    primary key (program_id, group_id, action)
);

create table workops.project_permissions (
    project_id  uuid              not null references workops.project(id) on delete cascade,
    group_id    uuid              not null,
    action      permission_action not null,
    primary key (project_id, group_id, action)
);

create table workops.task_permissions (
    task_id   uuid              not null references workops.task(id) on delete cascade,
    group_id  uuid              not null,
    action    permission_action not null,
    primary key (task_id, group_id, action)
);

-- 3. Drop old permission-scheme system ----------------------------------------

alter table workops.project drop constraint if exists project_default_permission_scheme_fk;
alter table workops.project drop column if exists default_permission_scheme_id;

drop table if exists workops.project_role_membership;
drop table if exists workops.project_role;
drop table if exists workops.permission_scheme;
