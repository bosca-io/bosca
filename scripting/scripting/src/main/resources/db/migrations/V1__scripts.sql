create table scripting.scripts
(
    id            uuid                     not null default gen_random_uuid() primary key,
    key           varchar                  not null unique,
    name          varchar                  not null,
    description   varchar                  not null default '',
    type          varchar                  not null default 'general',
    source        text                     not null,
    version       int                      not null default 1,
    enabled       boolean                  not null default true,
    input_schema  jsonb,
    output_schema jsonb,
    configuration jsonb,
    created       timestamp with time zone not null default now(),
    modified      timestamp with time zone not null default now()
);

create index idx_scripts_type on scripting.scripts (type) where enabled = true;

create table scripting.trigger_bindings
(
    id         uuid    not null default gen_random_uuid() primary key,
    script_id  uuid    not null references scripting.scripts (id) on delete cascade,
    event_name varchar not null,
    filter     jsonb,
    ordinal    int     not null default 0,
    enabled    boolean not null default true
);

create index idx_trigger_bindings_event on scripting.trigger_bindings (event_name) where enabled = true;
create index idx_trigger_bindings_script on scripting.trigger_bindings (script_id);
