-- No FK to git.repositories: public-schema migrations run before non-public schemas
-- (FlywayMigration partitions by schema), so the git.repositories relation does not
-- yet exist at this point. Same precedent as workops.spec.git_repository_id.

alter table prompts
    add column git_repository_id uuid,
    add column git_path          varchar,
    add column last_sync_error   text,
    add constraint prompts_git_repository_path_unique unique (git_repository_id, git_path);
