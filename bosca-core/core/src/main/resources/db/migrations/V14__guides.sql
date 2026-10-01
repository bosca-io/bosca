create type guide_type as enum ('linear', 'linear_progress', 'calendar', 'calendar_progress');

create type attribute_type as enum ('string', 'int', 'float', 'date', 'datetime', 'profile', 'metadata', 'collection');
create type attribute_ui_type as enum ('input', 'textarea', 'image', 'profile', 'file', 'metadata', 'collection');

create table guide_templates
(
    metadata_id        uuid       not null,
    version            int        not null,
    rrule              varchar,
    type               guide_type not null,
    default_attributes jsonb,
    primary key (metadata_id, version),
    foreign key (metadata_id) references metadata (id) on delete cascade
);

create table guide_template_attributes
(
    metadata_id       uuid              not null,
    version           int               not null,
    key               varchar           not null,
    name              varchar           not null,
    description       varchar           not null,
    supplementary_key varchar,
    configuration     jsonb,
    type              attribute_type    not null,
    ui                attribute_ui_type not null,
    list              boolean           not null,
    sort              int               not null,
    primary key (metadata_id, version, key),
    foreign key (metadata_id, version) references guide_templates (metadata_id, version) on delete cascade
);

create table guide_template_attribute_workflows
(
    metadata_id uuid    not null,
    version     int     not null,
    key         varchar not null,
    workflow_id varchar not null,
    auto_run    bool    not null default false,
    primary key (metadata_id, version, key, workflow_id),
    foreign key (metadata_id, version, key) references guide_template_attributes (metadata_id, version, key) on delete cascade,
    foreign key (workflow_id) references workflows (id)
);

create table guide_template_steps
(
    metadata_id               uuid      not null,
    version                   int       not null,
    id                        bigserial not null,
    template_metadata_id      uuid,
    template_metadata_version int,
    sort                      int       not null,
    primary key (metadata_id, version, id),
    foreign key (metadata_id, version) references guide_templates (metadata_id, version) on delete cascade,
    foreign key (template_metadata_id) references metadata (id) on delete cascade
);

create table guide_template_step_attributes
(
    metadata_id       uuid              not null,
    version           int               not null,
    step              bigint            not null,
    key               varchar           not null,
    name              varchar           not null,
    description       varchar           not null,
    supplementary_key varchar,
    configuration     jsonb,
    type              attribute_type    not null,
    ui                attribute_ui_type not null,
    list              boolean           not null,
    sort              int               not null,
    primary key (metadata_id, version, step, key),
    foreign key (metadata_id, version, step) references guide_template_steps (metadata_id, version, id) on delete cascade
);

create table guide_template_step_attribute_workflows
(
    metadata_id uuid    not null,
    version     int     not null,
    step        bigint  not null,
    key         varchar not null,
    workflow_id varchar not null,
    auto_run    bool    not null default false,
    primary key (metadata_id, version, step, key, workflow_id),
    foreign key (metadata_id, version, step, key) references guide_template_step_attributes (metadata_id, version, step, key) on delete cascade,
    foreign key (workflow_id) references workflows (id)
);

create table guide_template_step_modules
(
    metadata_id               uuid      not null,
    version                   int       not null,
    step                      bigint    not null,
    id                        bigserial not null,

    template_metadata_id      uuid      not null,
    template_metadata_version int       not null,

    sort                      int       not null,
    primary key (metadata_id, version, step, id),
    foreign key (metadata_id, version, step) references guide_template_steps (metadata_id, version, id) on delete cascade,
    foreign key (template_metadata_id) references metadata (id)
);

create table guides
(
    metadata_id               uuid       not null,
    version                   int        not null,
    rrule                     varchar,
    type                      guide_type not null,

    template_metadata_id      uuid,
    template_metadata_version int,

    primary key (metadata_id, version),
    foreign key (metadata_id) references metadata (id) on delete cascade,
    foreign key (template_metadata_id, template_metadata_version) references guide_templates (metadata_id, version)
);

create table guide_steps
(
    metadata_id               uuid      not null,
    version                   int       not null,
    id                        bigserial not null,

    template_metadata_id      uuid,
    template_metadata_version int,
    template_step             int,

    step_metadata_id          uuid      not null,
    step_metadata_version     int       not null,

    sort                      int       not null,
    primary key (metadata_id, version, id),
    foreign key (metadata_id, version) references guides (metadata_id, version) on delete cascade,
    foreign key (step_metadata_id) references metadata (id) on delete cascade,
    foreign key (template_metadata_id, template_metadata_version, template_step) references guide_template_steps (metadata_id, version, id)
);

create table guide_step_modules
(
    metadata_id               uuid   not null,
    version                   int    not null,
    step                      bigint not null,
    id                        bigint not null,

    template_metadata_id      uuid,
    template_metadata_version int,
    template_step             bigint,
    template_module           bigint,

    module_metadata_id        uuid   not null,
    module_metadata_version   int    not null,

    sort                      int    not null,
    primary key (metadata_id, version, step, id),
    foreign key (metadata_id) references metadata (id),
    foreign key (metadata_id, version, step) references guide_steps (metadata_id, version, id) on delete cascade,
    foreign key (template_metadata_id, template_metadata_version, template_step,
                 template_module) references guide_template_step_modules (metadata_id, version, step, id)
);

create table profile_guide_progress
(
    profile_id         uuid     not null,
    metadata_id        uuid     not null,
    version            int      not null,
    completed_step_ids bigint[] not null        default '{}',
    attributes         jsonb,
    started            timestamp with time zone default now(),
    modified           timestamp with time zone default now(),
    completed          timestamp with time zone,
    primary key (profile_id, metadata_id, version),
    foreign key (profile_id) references profiles (id),
    foreign key (metadata_id) references metadata (id)
);

create table profile_guide_history
(
    id          bigserial not null,
    profile_id  uuid      not null,
    metadata_id uuid      not null,
    version     int       not null,
    attributes  jsonb,
    completed   timestamp with time zone,
    primary key (id),
    foreign key (profile_id) references profiles (id),
    foreign key (metadata_id) references metadata (id)
);

create or replace function guide_history_trigger() returns trigger AS
$version_trigger$
begin
    if new.completed is not null then
        insert into profile_guide_history (profile_id, metadata_id, version, attributes, completed)
        values (new.profile_id, new.metadata_id, new.version, new.attributes, new.completed);
    end if;
    return new;
end;
$version_trigger$ language plpgsql;

create trigger guide_progress_history
    after update
    on profile_guide_history
    for each row
execute function guide_history_trigger();