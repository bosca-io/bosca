alter table git.github_repository_pairs
    add column push_branch_includes varchar[] not null default '{}',
    add column push_branch_excludes varchar[] not null default '{}',
    add column pull_branch_includes varchar[] not null default '{}',
    add column pull_branch_excludes varchar[] not null default '{}';
