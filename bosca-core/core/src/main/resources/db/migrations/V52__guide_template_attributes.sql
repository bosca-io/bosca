-- Add template configuration columns to guide_templates
alter table guide_templates add column default_attributes jsonb;
alter table guide_templates add column configuration jsonb;

-- Create guide template attributes table for template editor
create table guide_template_attributes
(
    metadata_id       uuid    not null,
    version           integer not null,
    key               text    not null,
    name              text    not null,
    description       text,
    supplementary_key text,
    configuration     jsonb,
    type              text    not null,
    ui                text    not null,
    list              boolean not null default false,
    sort              integer not null,
    primary key (metadata_id, version, key),
    foreign key (metadata_id, version) references guide_templates (metadata_id, version) on delete cascade
); 