create table form_templates
(
    metadata_id        uuid  not null,
    version            int   not null,
    configuration      jsonb,
    default_attributes jsonb,
    primary key (metadata_id, version),
    foreign key (metadata_id) references metadata (id) on delete cascade
);

create table form_template_attributes
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
    list              boolean           not null default false,
    sort              int               not null default 0,
    tools             jsonb,
    primary key (metadata_id, version, key),
    foreign key (metadata_id, version) references form_templates (metadata_id, version) on delete cascade
);

create table form_submissions
(
    id                     uuid primary key default gen_random_uuid(),
    form_template_id       uuid    not null,
    form_template_version  int     not null,
    profile_id             uuid    not null references profiles (id) on delete cascade,
    attributes             jsonb   not null,
    status                 varchar not null default 'PENDING',
    created                timestamp with time zone not null default now(),
    modified               timestamp with time zone not null default now(),
    foreign key (form_template_id, form_template_version) references form_templates (metadata_id, version) on delete cascade
);

create index idx_form_submissions_template on form_submissions (form_template_id, form_template_version);
create index idx_form_submissions_profile on form_submissions (profile_id);
create index idx_form_submissions_status on form_submissions (status);
create index idx_form_submissions_created on form_submissions (created desc);
