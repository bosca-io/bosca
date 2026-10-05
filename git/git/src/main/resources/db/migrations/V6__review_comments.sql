alter table git.reviews add column dismissed_at timestamptz;
alter table git.reviews add column dismiss_reason varchar;

create table git.review_comments (
    id                uuid primary key default gen_random_uuid(),
    review_id         uuid not null references git.reviews(id) on delete cascade,
    pull_request_id   uuid not null references git.pull_requests(id) on delete cascade,
    author_id         uuid not null,
    file_path         varchar not null,
    old_line_number   int,
    new_line_number   int,
    commit_sha        varchar not null,
    content           text not null,
    outdated          boolean not null default false,
    resolved          boolean not null default false,
    created           timestamptz not null default now(),
    updated           timestamptz not null default now()
);
create index idx_git_review_comments_review on git.review_comments (review_id);
create index idx_git_review_comments_pr on git.review_comments (pull_request_id);
