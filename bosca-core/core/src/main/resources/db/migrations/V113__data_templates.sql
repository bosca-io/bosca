create table data_templates
(
    metadata_id        uuid  not null,
    version            int   not null,
    default_attributes jsonb,
    primary key (metadata_id, version),
    foreign key (metadata_id) references metadata (id) on delete cascade
);

create table data_template_attributes
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
    tools             jsonb,
    primary key (metadata_id, version, key),
    foreign key (metadata_id, version) references data_templates (metadata_id, version) on delete cascade
);

create table data_template_attribute_workflows
(
    metadata_id uuid    not null,
    version     int     not null,
    key         varchar not null,
    workflow_id varchar not null,
    auto_run    bool    not null default false,
    primary key (metadata_id, version, key, workflow_id),
    foreign key (metadata_id, version, key) references data_template_attributes (metadata_id, version, key) on delete cascade,
    foreign key (workflow_id) references workflows (id)
);
