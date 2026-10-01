-- No FK to git.repositories: the pipelines-schema migration's dependsOn does not include
-- "git", so they may run concurrently. Application enforces referential integrity
-- via git's soft-delete-with-retention model. Same precedent as ai.agents.git_repository_id.

alter table pipelines.pipelines
    add column git_repository_id uuid,
    add column git_path          varchar,
    add column last_sync_error   text,
    add constraint pipelines_git_repository_path_unique unique (git_repository_id, git_path);
