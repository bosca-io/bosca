-- Durable mobile-store build numbers (GIT-SPEC-6 / GIT-61).
-- The counter is global per store application identity. Allocations are immutable and idempotent
-- for one repository/source/version/variant, so a job retry cannot burn or change a build number.

create table workops.app_build_number_counter (
    platform       varchar     not null,
    application_id varchar     not null,
    last_number    bigint      not null default 0,
    modified_at    timestamptz not null default now(),
    version        bigint      not null default 0,
    primary key (platform, application_id),
    constraint app_build_number_counter_platform check (platform in ('ANDROID', 'IOS')),
    constraint app_build_number_counter_range check (
        (platform = 'ANDROID' and last_number between 0 and 2100000000) or
        (platform = 'IOS' and last_number between 0 and 99990000)
    )
);

create table workops.app_build_number_allocation (
    id                uuid        not null default gen_random_uuid() primary key,
    platform          varchar     not null,
    application_id    varchar     not null,
    build_key         varchar     not null default 'default',
    repository_id     uuid        not null,
    source_commit_sha varchar     not null,
    source_version    varchar     not null,
    pipeline_run_id   uuid        not null,
    number            bigint      not null,
    value             varchar     not null,
    created_at        timestamptz not null default now(),
    constraint app_build_number_allocation_platform check (platform in ('ANDROID', 'IOS')),
    constraint app_build_number_allocation_range check (
        (platform = 'ANDROID' and number between 1 and 2100000000) or
        (platform = 'IOS' and number between 1 and 99990000)
    ),
    unique (repository_id, source_commit_sha, source_version, platform, application_id, build_key),
    unique (platform, application_id, number)
);

create index app_build_number_source_idx
    on workops.app_build_number_allocation(repository_id, source_commit_sha, source_version);

alter table workops.environment_deployment
    add column app_build_number_allocation_id uuid
        references workops.app_build_number_allocation(id) on delete set null;

create index environment_deployment_app_build_number_idx
    on workops.environment_deployment(app_build_number_allocation_id)
    where app_build_number_allocation_id is not null;
