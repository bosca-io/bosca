create table api_documentation
(
    qualified_name varchar                  not null primary key,
    content        jsonb                    not null,
    indexed_at     timestamp with time zone not null default now()
);
