create table data
(
    metadata_id               uuid      not null,
    version                   int       not null,
    template_metadata_id      uuid,
    template_metadata_version int,
    type                      data_type not null default 'attributes',
    primary key (metadata_id, version),
    foreign key (metadata_id) references metadata (id) on delete cascade
);

alter table data_templates
    drop column template_metadata_id,
    drop column template_metadata_version;
