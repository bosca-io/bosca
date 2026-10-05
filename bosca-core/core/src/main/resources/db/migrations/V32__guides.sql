drop table guide_template_attribute_workflows;
drop table guide_template_attributes;
drop table guide_template_step_attribute_workflows;
drop table guide_template_step_attributes;
drop table guide_step_modules;
drop table guide_template_step_modules;
drop table guide_steps;
drop table guide_template_steps;
drop table guides;
drop table guide_templates;

create table guide_templates
(
    metadata_id uuid       not null,
    version     int        not null,
    rrule       varchar,
    type        guide_type not null,
    primary key (metadata_id, version),
    foreign key (metadata_id) references metadata (id) on delete cascade
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
    metadata_id           uuid      not null,
    version               int       not null,
    id                    bigserial not null,
    step_metadata_id      uuid      not null,
    step_metadata_version int       not null,
    sort                  int       not null,
    primary key (metadata_id, version, id),
    foreign key (metadata_id, version) references guides (metadata_id, version) on delete cascade,
    foreign key (step_metadata_id) references metadata (id) on delete cascade
);

create table guide_step_modules
(
    metadata_id             uuid      not null,
    version                 int       not null,
    step                    bigint    not null,
    id                      bigserial not null,
    module_metadata_id      uuid      not null,
    module_metadata_version int       not null,
    sort                    int       not null,
    primary key (metadata_id, version, step, id),
    foreign key (metadata_id) references metadata (id),
    foreign key (metadata_id, version, step) references guide_steps (metadata_id, version, id) on delete cascade,
    foreign key (module_metadata_id) references metadata (id) on delete cascade
);

alter table profile_guide_progress
    drop column completed_step_ids;

create table profile_guide_progress_steps
(
    profile_id  uuid   not null,
    metadata_id uuid   not null,
    version     int    not null,
    step_id     bigint not null,
    primary key (profile_id, metadata_id, version, step_id),
    foreign key (profile_id, metadata_id, version) references profile_guide_progress (profile_id, metadata_id, version) on delete cascade,
    foreign key (metadata_id, version, step_id) references guide_steps (metadata_id, version, id)
);

create or replace function guide_history_trigger() returns trigger AS
$version_trigger$
begin
    if new.completed is not null then
        insert into profile_guide_history (profile_id, metadata_id, version, attributes, completed)
        values (new.profile_id, new.metadata_id, new.version, new.attributes, new.completed);
        delete
        from profile_guide_progress_steps
        where profile_id = new.profile_id
          and metadata_id = new.metadata_id
          and version = new.version;
    end if;
    return new;
end;
$version_trigger$ language plpgsql;