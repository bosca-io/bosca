create table git.script_source_refs (
    script_id        uuid    not null,
    repository_id    uuid    not null references git.repositories(id) on delete cascade,
    path             varchar not null,
    ref              varchar not null default 'main',
    resolved_commit  varchar,
    primary key (script_id)
);
create index idx_git_script_source_refs_repo on git.script_source_refs (repository_id);

create table git.query_source_refs (
    query_id         uuid    not null,
    repository_id    uuid    not null references git.repositories(id) on delete cascade,
    path             varchar not null,
    ref              varchar not null default 'main',
    resolved_commit  varchar,
    primary key (query_id)
);
create index idx_git_query_source_refs_repo on git.query_source_refs (repository_id);
