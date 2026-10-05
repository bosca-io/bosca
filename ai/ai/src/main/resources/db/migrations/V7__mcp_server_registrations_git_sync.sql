-- No FK to git.repositories: the ai-schema migration's dependsOn does not include
-- "git", so they may run concurrently. Application enforces referential integrity
-- via git's soft-delete-with-retention model. Same precedent as workops.spec.git_repository_id.

alter table ai.mcp_server_registrations
    add column git_repository_id uuid,
    add column git_path          varchar,
    add column last_sync_error   text,
    add constraint mcp_server_registrations_git_repository_path_unique unique (git_repository_id, git_path);
