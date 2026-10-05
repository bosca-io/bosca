create table git.pull_request_dependencies (
    pull_request_id            uuid not null references git.pull_requests(id) on delete cascade,
    depends_on_pull_request_id uuid not null references git.pull_requests(id) on delete cascade,
    created                    timestamptz not null default now(),
    primary key (pull_request_id, depends_on_pull_request_id),
    check (pull_request_id <> depends_on_pull_request_id)
);

create index idx_git_pull_request_dependencies_reverse
    on git.pull_request_dependencies (depends_on_pull_request_id, pull_request_id);
