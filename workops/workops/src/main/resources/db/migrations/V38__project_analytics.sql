-- V38: Per-project analytics identifiers (WORKOPS-SPEC-22/23 release health telemetry).
--
-- A project owns zero or more analytics applications (the appId in sessions.<appId> counters and error
-- groups) and zero or more analytics services (the service in http.<service> response-code counters).
-- The release dashboard reads these across a release's projects to show session/error/response-code
-- health at a high level. Managed on the project.

create table workops.project_analytics_application (
    id             uuid not null default gen_random_uuid() primary key,
    project_id     uuid not null references workops.project(id) on delete cascade,
    application_id text not null,
    unique (project_id, application_id)
);

create table workops.project_analytics_service (
    id           uuid not null default gen_random_uuid() primary key,
    project_id   uuid not null references workops.project(id) on delete cascade,
    service      text not null,
    unique (project_id, service)
);

create index project_analytics_application_project_idx on workops.project_analytics_application(project_id);
create index project_analytics_service_project_idx on workops.project_analytics_service(project_id);
