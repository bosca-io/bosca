create table form_schemas (
    id                    uuid primary key default gen_random_uuid(),
    key                   varchar not null unique,
    name                  varchar not null,
    description           varchar not null default '',
    schema                jsonb   not null,
    ui_schema             jsonb   not null,
    version               int     not null default 1,
    public                boolean not null default false,
    public_content        boolean not null default false,
    public_list           boolean not null default false,
    public_supplementary  boolean not null default false,
    published             boolean not null default false,
    deleted               boolean not null default false,
    created               timestamp with time zone not null default now(),
    modified              timestamp with time zone not null default now()
);

create table form_schema_permissions (
    form_schema_id uuid        not null,
    group_id       uuid        not null,
    action         permission_action not null,
    primary key (form_schema_id, group_id, action),
    foreign key (form_schema_id) references form_schemas (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);

create index idx_form_schema_permissions_form_schema on form_schema_permissions (form_schema_id);
