create type document_block_type as enum ('text', 'richtext', 'video', 'audio', 'image', 'supplementary');
create type document_metadata_attribute_type as enum ('string', 'int', 'float', 'date', 'datetime');

create table document_templates
(
    id          bigserial not null,
    name        varchar   not null,
    description varchar   not null,
    created     timestamp with time zone default now(),
    modified    timestamp with time zone default now(),
    primary key (id)
);

create table document_template_metadata_attributes
(
    id          bigint                           not null,
    key         varchar                          not null,
    name        varchar                          not null,
    description varchar                          not null,
    type        document_metadata_attribute_type not null,
    primary key (id, key),
    foreign key (id) references document_templates on delete cascade
);

create table document_template_metadata_attribute_workflow_ids
(
    id          bigint  not null,
    key         varchar not null,
    workflow_id varchar not null,
    auto_run    bool    not null default false,
    primary key (id, key, workflow_id),
    foreign key (id, key) references document_template_metadata_attributes (id, key) on delete cascade
);

create table document_template_blocks
(
    template_id bigint              not null,
    id          bigserial           not null,
    name        varchar             not null,
    description varchar             not null,
    type        document_block_type not null,
    sort        int                 not null,
    primary key (template_id, id),
    foreign key (template_id) references document_templates (id) on delete cascade
);

create table documents
(
    metadata_id uuid    not null,
    version     int     not null,
    template_id bigint,
    title       varchar not null,
    primary key (metadata_id, version),
    foreign key (metadata_id) references metadata (id) on delete cascade,
    foreign key (template_id) references document_templates (id)
);

create table document_blocks
(
    metadata_id       uuid                not null,
    version           int                 not null,
    template_id       bigint,
    template_block_id bigint,
    id                bigserial           not null,
    type              document_block_type not null,
    sort              int                 not null,
    text              varchar,
    richtext          jsonb,
    primary key (metadata_id, version, id),
    foreign key (metadata_id, version) references documents (metadata_id, version) on delete cascade,
    foreign key (template_id, template_block_id) references document_template_blocks (template_id, id)
);

create table document_block_metadata_references
(
    metadata_id           uuid   not null,
    version               int    not null,
    block_id              bigint not null,
    metadata_reference_id uuid   not null,
    attributes            json,
    sort                  int    not null,
    primary key (metadata_id, version, block_id, metadata_reference_id),
    foreign key (metadata_id, version, block_id) references document_blocks (metadata_id, version, id) on delete cascade
);