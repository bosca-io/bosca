-- V42: git repositories a project owns (WORKOPS-SPEC-22/23). The release relay reads a project's repos
-- from data (to tag/build them) instead of baking a repositoryId into pipeline nodes. A project owns zero
-- or more; managed on the project. repository_id references a git-module Repository (no cross-module FK).
create table workops.project_repository (
    id            uuid not null default gen_random_uuid() primary key,
    project_id    uuid not null references workops.project(id) on delete cascade,
    repository_id uuid not null,
    unique (project_id, repository_id)
);

create index project_repository_project_idx on workops.project_repository(project_id);
