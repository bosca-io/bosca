create table git.task_commit_references (
    id              uuid primary key default gen_random_uuid(),
    repository_id   uuid not null references git.repositories(id) on delete cascade,
    task_key        varchar not null,
    commit_sha      varchar not null,
    created         timestamptz not null default now()
);
create index idx_git_task_commit_ref_repo_key on git.task_commit_references (repository_id, task_key);
create index idx_git_task_commit_ref_sha on git.task_commit_references (commit_sha);

create table git.task_pull_request_references (
    id                  uuid primary key default gen_random_uuid(),
    repository_id       uuid not null references git.repositories(id) on delete cascade,
    task_key            varchar not null,
    pull_request_id     uuid not null references git.pull_requests(id) on delete cascade,
    pull_request_number int not null,
    created             timestamptz not null default now()
);
create index idx_git_task_pr_ref_repo_key on git.task_pull_request_references (repository_id, task_key);
create index idx_git_task_pr_ref_pr_id on git.task_pull_request_references (pull_request_id);
