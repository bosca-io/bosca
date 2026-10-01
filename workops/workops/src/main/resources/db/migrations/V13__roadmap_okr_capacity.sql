-- Work Ops — Phase 9 (specs/workops/plan.md §9.1-9.4, R18-R20)
--
-- Roadmap scenarios + OKRs + capacity. Each subsystem owns a
-- single table; rollups happen in services rather than triggers
-- so the audit trail remains fully attributable.

create table workops.roadmap_scenario (
    id                       uuid    not null default gen_random_uuid() primary key,
    program_id               uuid    not null references workops.program(id) on delete cascade,
    name                     varchar not null,
    description              varchar,
    overrides                jsonb   not null default '{}'::jsonb,
    created_by_profile_id    uuid    not null,
    created_at               timestamptz not null default now(),
    version                  bigint  not null default 0,
    unique (program_id, name)
);

create table workops.objective (
    id                  uuid    not null default gen_random_uuid() primary key,
    portfolio_id        uuid    references workops.portfolio(id) on delete cascade,
    program_id          uuid    references workops.program(id) on delete cascade,
    project_id          uuid    references workops.project(id) on delete cascade,
    title               varchar not null,
    description         varchar,
    state               varchar not null default 'ACTIVE',
    period_start        timestamptz not null,
    period_end          timestamptz not null,
    period_name         varchar not null,
    owner_profile_id    uuid    not null,
    confidence          varchar not null default 'MEDIUM',
    version             bigint  not null default 0,
    -- Exactly one of the three scope FKs is required.
    check ((portfolio_id is not null)::int +
           (program_id   is not null)::int +
           (project_id   is not null)::int = 1)
);

create index objective_portfolio_idx on workops.objective(portfolio_id) where portfolio_id is not null;
create index objective_program_idx   on workops.objective(program_id)   where program_id is not null;
create index objective_project_idx   on workops.objective(project_id)   where project_id is not null;

create table workops.key_result (
    id              uuid    not null default gen_random_uuid() primary key,
    objective_id    uuid    not null references workops.objective(id) on delete cascade,
    title           varchar not null,
    description     varchar,
    -- Discriminator copied out of the JSONB blob so admins can
    -- list "all TaskCompletion KRs" without opening every row.
    metric_type     varchar not null,
    metric          jsonb   not null default '{}'::jsonb,
    current_value   double precision,
    computed_at     timestamptz,
    confidence      varchar not null default 'MEDIUM',
    version         bigint  not null default 0
);

create index key_result_objective_idx on workops.key_result(objective_id);

create table workops.capacity (
    sprint_id           uuid    not null references workops.sprint(id) on delete cascade,
    profile_id          uuid    not null,
    committed_seconds   bigint  not null check (committed_seconds >= 0),
    notes               varchar,
    primary key (sprint_id, profile_id)
);

-- Project-level enforce flag.
alter table workops.project
    add column if not exists enforce_capacity boolean not null default false;
