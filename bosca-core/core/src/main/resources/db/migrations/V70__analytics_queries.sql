drop table if exists analytic_queries;

create table analytics_queries
(
    id            uuid not null default gen_random_uuid(),
    name          text not null,
    description   text not null,
    query         text not null,
    configuration jsonb,
    primary key (id)
);

create type analytics_query_parameter_type as enum (
    'string',
    'integer',
    'float',
    'boolean',
    'date',
    'time',
    'datetime',
    'array',
    'object',
    'none'
    );

create table analytics_query_parameters
(
    query_id      uuid                           not null,
    parameter     text                           not null,
    name          text                           not null,
    description   text,
    type          analytics_query_parameter_type not null,
    array_type    analytics_query_parameter_type,
    default_value jsonb,
    required      boolean                        not null default false,
    sort          int                            not null,
    foreign key (query_id) references analytics_queries (id),
    primary key (query_id, parameter)
);

create table analytics_query_permissions
(
    query_id uuid              not null,
    group_id uuid              not null,
    action   permission_action not null,
    primary key (query_id, group_id, action),
    foreign key (query_id) references analytics_queries (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);

