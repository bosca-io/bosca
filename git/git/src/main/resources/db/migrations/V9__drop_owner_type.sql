drop index if exists git.idx_git_repositories_owner;
alter table git.repositories drop column if exists owner_type;
create index idx_git_repositories_owner on git.repositories (owner_id);
