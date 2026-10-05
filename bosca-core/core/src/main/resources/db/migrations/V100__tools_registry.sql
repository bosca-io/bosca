create table template_attribute_tools
(
    id            uuid    not null default gen_random_uuid(),
    key           varchar not null,
    name          varchar not null,
    description   varchar,
    query         text    not null,
    result_path   varchar,
    configuration jsonb,
    primary key (id),
    unique (name),
    unique (key)
);
