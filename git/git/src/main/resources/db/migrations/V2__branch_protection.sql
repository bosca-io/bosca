create table git.branch_protection_rules (
    id                      uuid primary key default gen_random_uuid(),
    repository_id           uuid not null references git.repositories(id) on delete cascade,
    pattern                 varchar not null,
    require_pull_request    boolean not null default false,
    required_approvals      int not null default 1,
    dismiss_stale_reviews   boolean not null default false,
    require_code_owner_review boolean not null default false,
    require_status_checks   varchar[] not null default '{}',
    require_linear_history  boolean not null default false,
    allow_force_push        boolean not null default false,
    allow_deletion          boolean not null default false,
    restrict_push_access    uuid[],
    created                 timestamptz not null default now(),
    updated                 timestamptz not null default now()
);
create index idx_git_branch_protection_repo on git.branch_protection_rules (repository_id);
