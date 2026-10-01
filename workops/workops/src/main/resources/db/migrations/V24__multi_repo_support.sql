-- V24: Multi-repo support entities for dependency graph, artifact tracking,
-- CI/CD visibility, environment management, and release gates.

-- ── Dependency declarations between projects ──────────────────────────

create table workops.dependency_declaration (
    id                           uuid        not null default gen_random_uuid() primary key,
    consumer_project_id          uuid        not null references workops.project(id) on delete cascade,
    consumer_version_id          uuid                 references workops.version(id) on delete set null,
    provider_project_id          uuid        not null references workops.project(id) on delete cascade,
    provider_version_constraint  varchar     not null,
    resolved_provider_version_id uuid                 references workops.version(id) on delete set null,
    dependency_type              varchar     not null,
    artifact_coordinates         varchar,
    status                       varchar     not null default 'CURRENT',
    version                      bigint      not null default 0,
    constraint dep_decl_no_self check (consumer_project_id != provider_project_id)
);

create index dep_decl_consumer_idx on workops.dependency_declaration(consumer_project_id);
create index dep_decl_provider_idx on workops.dependency_declaration(provider_project_id);
create index dep_decl_status_idx on workops.dependency_declaration(status) where status != 'CURRENT';
create unique index dep_decl_unique_idx
    on workops.dependency_declaration(consumer_project_id, provider_project_id, dependency_type)
    where consumer_version_id is null;

-- ── Artifact publication tracking ─────────────────────────────────────

create table workops.artifact_publication (
    id                        uuid        not null default gen_random_uuid() primary key,
    version_id                uuid        not null references workops.version(id) on delete cascade,
    project_id                uuid        not null references workops.project(id) on delete cascade,
    artifact_type             varchar     not null,
    coordinates               varchar     not null,
    repository_url            varchar,
    published_at              timestamptz,
    published_by_principal_id uuid,
    checksum_sha256           varchar,
    status                    varchar     not null default 'PENDING',
    external_url              varchar,
    version                   bigint      not null default 0
);

create index art_pub_version_idx on workops.artifact_publication(version_id);
create index art_pub_project_idx on workops.artifact_publication(project_id);
create index art_pub_status_idx on workops.artifact_publication(status) where status != 'PUBLISHED';
create unique index art_pub_coords_idx on workops.artifact_publication(coordinates);

-- ── API surface analysis reports ──────────────────────────────────────

create table workops.api_surface_report (
    id                      uuid        not null default gen_random_uuid() primary key,
    project_id              uuid        not null references workops.project(id) on delete cascade,
    version_id              uuid        not null references workops.version(id) on delete cascade,
    previous_version_id     uuid        not null references workops.version(id) on delete cascade,
    artifact_publication_id uuid                 references workops.artifact_publication(id) on delete set null,
    breaking_change_level   varchar     not null,
    changes                 jsonb       not null default '[]'::jsonb,
    analyzed_at             timestamptz not null default now(),
    analyzer_tool           varchar     not null,
    report_url              varchar,
    version                 bigint      not null default 0
);

create index api_surface_version_idx on workops.api_surface_report(version_id);
create index api_surface_breaking_idx on workops.api_surface_report(breaking_change_level)
    where breaking_change_level not in ('NONE', 'DEPRECATION');

-- ── CI/CD pipeline run tracking ───────────────────────────────────────

create table workops.pipeline_run (
    id            uuid        not null default gen_random_uuid() primary key,
    project_id    uuid        not null references workops.project(id) on delete cascade,
    version_id    uuid                 references workops.version(id) on delete set null,
    pipeline_id   varchar     not null,
    pipeline_name varchar     not null,
    trigger_type  varchar     not null,
    trigger_ref   varchar,
    status        varchar     not null default 'PENDING',
    started_at    timestamptz not null default now(),
    completed_at  timestamptz,
    external_url  varchar,
    version       bigint      not null default 0
);

create table workops.pipeline_stage_run (
    id              uuid        not null default gen_random_uuid() primary key,
    pipeline_run_id uuid        not null references workops.pipeline_run(id) on delete cascade,
    stage_name      varchar     not null,
    status          varchar     not null default 'PENDING',
    started_at      timestamptz,
    completed_at    timestamptz,
    external_url    varchar
);

create index pipeline_run_project_idx on workops.pipeline_run(project_id);
create index pipeline_run_version_idx on workops.pipeline_run(version_id) where version_id is not null;
create index pipeline_run_active_idx on workops.pipeline_run(status) where status in ('PENDING', 'RUNNING');
create index pipeline_stage_run_idx on workops.pipeline_stage_run(pipeline_run_id);

-- ── Environment and deployment tracking ───────────────────────────────

create table workops.environment (
    id                  uuid        not null default gen_random_uuid() primary key,
    program_id          uuid        not null references workops.program(id) on delete cascade,
    name                varchar     not null,
    description         varchar,
    display_order       integer     not null default 0,
    promotion_source_id uuid                 references workops.environment(id) on delete set null,
    requires_approval   boolean     not null default false,
    auto_promote        boolean     not null default false,
    version             bigint      not null default 0,
    unique (program_id, name)
);

create table workops.environment_deployment (
    id                       uuid        not null default gen_random_uuid() primary key,
    environment_id           uuid        not null references workops.environment(id) on delete cascade,
    project_id               uuid        not null references workops.project(id) on delete cascade,
    version_id               uuid        not null references workops.version(id) on delete cascade,
    release_id               uuid                 references workops.release(id) on delete set null,
    artifact_publication_id  uuid                 references workops.artifact_publication(id) on delete set null,
    status                   varchar     not null default 'PENDING',
    deployed_at              timestamptz,
    deployed_by_principal_id uuid,
    health_check_status      varchar     not null default 'UNKNOWN',
    health_check_url         varchar,
    last_health_check_at     timestamptz,
    previous_deployment_id   uuid                 references workops.environment_deployment(id) on delete set null,
    version                  bigint      not null default 0
);

create index env_program_idx on workops.environment(program_id);
create index env_deploy_env_idx on workops.environment_deployment(environment_id);
create index env_deploy_project_idx on workops.environment_deployment(project_id);
create index env_deploy_active_idx
    on workops.environment_deployment(environment_id, project_id)
    where status = 'DEPLOYED';

-- ── Release gates ─────────────────────────────────────────────────────

create table workops.release_gate (
    id                        uuid        not null default gen_random_uuid() primary key,
    release_id                uuid        not null references workops.release(id) on delete cascade,
    name                      varchar     not null,
    gate_type                 varchar     not null,
    bql_expression            varchar,
    status                    varchar     not null default 'PENDING',
    evaluated_at              timestamptz,
    evaluated_by_principal_id uuid,
    waive_reason              varchar,
    version                   bigint      not null default 0,
    unique (release_id, name)
);

create index release_gate_release_idx on workops.release_gate(release_id);
create index release_gate_pending_idx on workops.release_gate(release_id)
    where status in ('PENDING', 'FAILED');

-- ── Compatibility test results ────────────────────────────────────────

create table workops.compatibility_test_result (
    id                    uuid        not null default gen_random_uuid() primary key,
    consumer_project_id   uuid        not null references workops.project(id) on delete cascade,
    consumer_version_id   uuid        not null references workops.version(id) on delete cascade,
    provider_project_id   uuid        not null references workops.project(id) on delete cascade,
    provider_version_id   uuid        not null references workops.version(id) on delete cascade,
    test_suite            varchar     not null,
    status                varchar     not null default 'PENDING',
    breaking_changes_detected boolean not null default false,
    pipeline_run_id       uuid                 references workops.pipeline_run(id) on delete set null,
    result_url            varchar,
    tested_at             timestamptz not null default now(),
    version               bigint      not null default 0
);

create index compat_consumer_idx
    on workops.compatibility_test_result(consumer_project_id, consumer_version_id);
create index compat_provider_idx
    on workops.compatibility_test_result(provider_project_id, provider_version_id);

-- ── Release notes ─────────────────────────────────────────────────────

create table workops.release_notes (
    id              uuid        not null default gen_random_uuid() primary key,
    release_id      uuid        not null references workops.release(id) on delete cascade unique,
    generated_at    timestamptz not null default now(),
    manually_edited boolean     not null default false,
    sections        jsonb       not null default '[]'::jsonb,
    version         bigint      not null default 0
);

-- ── Extend release_component_version with deployment tracking ─────────

alter table workops.release_component_version
    add column if not exists deployment_order integer,
    add column if not exists deployment_status varchar not null default 'PENDING',
    add column if not exists deployed_at timestamptz,
    add column if not exists deployed_by_principal_id uuid,
    add column if not exists rollback_version_id uuid references workops.version(id) on delete set null;
