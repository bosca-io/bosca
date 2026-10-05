create type git.pull_request_status as enum ('open', 'merged', 'closed', 'draft');
create type git.merge_strategy as enum ('merge_commit', 'squash', 'rebase', 'fast_forward');
create type git.review_status as enum ('approved', 'changes_requested', 'comment_only');

create table git.pull_requests (
    id                   uuid primary key default gen_random_uuid(),
    repository_id        uuid not null references git.repositories(id) on delete cascade,
    number               int not null,
    title                varchar not null,
    description          varchar,
    author_id            uuid not null,
    source_branch        varchar not null,
    target_branch        varchar not null,
    source_repository_id uuid references git.repositories(id),
    status               git.pull_request_status not null default 'open',
    merge_strategy       git.merge_strategy,
    merged_by            uuid,
    merged_at            timestamptz,
    merge_sha            varchar,
    created              timestamptz not null default now(),
    updated              timestamptz not null default now(),
    unique (repository_id, number)
);
create index idx_git_pull_requests_repo_status on git.pull_requests (repository_id, status);
create index idx_git_pull_requests_author on git.pull_requests (author_id);

create table git.pull_request_labels (
    pull_request_id uuid not null references git.pull_requests(id) on delete cascade,
    label           varchar not null,
    primary key (pull_request_id, label)
);

create table git.pull_request_assignees (
    pull_request_id uuid not null references git.pull_requests(id) on delete cascade,
    profile_id      uuid not null,
    primary key (pull_request_id, profile_id)
);

create table git.pull_request_reviewers (
    pull_request_id uuid not null references git.pull_requests(id) on delete cascade,
    profile_id      uuid not null,
    primary key (pull_request_id, profile_id)
);

create table git.reviews (
    id              uuid primary key default gen_random_uuid(),
    pull_request_id uuid not null references git.pull_requests(id) on delete cascade,
    reviewer_id     uuid not null,
    status          git.review_status not null,
    body            varchar,
    created         timestamptz not null default now()
);
create index idx_git_reviews_pr on git.reviews (pull_request_id);
