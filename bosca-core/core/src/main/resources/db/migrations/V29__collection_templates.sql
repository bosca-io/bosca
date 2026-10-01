alter table collections add column deleted boolean not null default false;
alter table metadata add column deleted boolean not null default false;

create table collection_templates
(
    metadata_id        uuid not null,
    version            int  not null,
    configuration      jsonb,
    default_attributes jsonb,
    collection_filter  jsonb,
    metadata_filter    jsonb,
    primary key (metadata_id, version),
    foreign key (metadata_id) references metadata (id) on delete cascade
);

create table collection_template_attributes
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
    foreign key (metadata_id, version) references collection_templates (metadata_id, version) on delete cascade
);

create table collection_template_attribute_workflows
(
    metadata_id uuid    not null,
    version     int     not null,
    key         varchar not null,
    workflow_id varchar not null,
    auto_run    bool    not null default false,
    primary key (metadata_id, version, key, workflow_id),
    foreign key (metadata_id, version, key) references collection_template_attributes (metadata_id, version, key) on delete cascade,
    foreign key (workflow_id) references workflows (id)
);

alter table collections
    add column template_metadata_id uuid;
alter table collections
    add column template_metadata_version int;

alter table collections
    add foreign key (template_metadata_id, template_metadata_version) references collection_templates (metadata_id, version) on delete set null;