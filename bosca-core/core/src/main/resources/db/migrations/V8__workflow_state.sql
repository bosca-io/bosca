create table workflow_plans
(
    id            uuid    not null,
    created       timestamp with time zone default now(),
    modified      timestamp with time zone default now(),
    configuration jsonb   not null,
    primary key (id)
);