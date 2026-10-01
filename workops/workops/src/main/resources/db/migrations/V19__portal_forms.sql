-- Work Ops — Phase 20 (specs/workops/plan.md §20, R28)
--
-- Versioned portal forms with conditional sections + bindings,
-- a portal-only FILE_UPLOAD field type, and per-submission
-- audit. Phase 14 shipped the bare submission shape; Phase 20
-- swaps it for the full builder.

create table workops.portal_form (
    id                          uuid    not null default gen_random_uuid() primary key,
    request_type_id             uuid    not null references workops.portal_request_type(id) on delete cascade,
    version                     integer not null default 1,
    submit_button_label         varchar not null default 'Submit',
    confirmation_markdown       varchar,
    created_at                  timestamptz not null default now(),
    archived_at                 timestamptz,
    unique (request_type_id, version)
);

create index portal_form_request_type_idx
    on workops.portal_form(request_type_id) where archived_at is null;

create table workops.portal_form_section (
    id                  uuid    not null default gen_random_uuid() primary key,
    portal_form_id      uuid    not null references workops.portal_form(id) on delete cascade,
    title               varchar not null,
    description_markdown varchar,
    display_order       integer not null default 0,
    visibility_condition jsonb  not null default '{}'::jsonb
);

create index portal_form_section_form_idx on workops.portal_form_section(portal_form_id, display_order);

create table workops.portal_form_field_binding (
    id                          uuid    not null default gen_random_uuid() primary key,
    section_id                  uuid    not null references workops.portal_form_section(id) on delete cascade,
    display_order               integer not null default 0,
    kind                        varchar not null,
    type                        varchar not null,
    label                       varchar not null,
    custom_field_key            varchar,
    transient_key               varchar,
    required                    boolean not null default false,
    help_text                   varchar,
    placeholder                 varchar,
    default_value_expression    varchar,
    visibility_condition        jsonb   not null default '{}'::jsonb,
    validation_expression       varchar
);

create index portal_form_field_binding_section_idx
    on workops.portal_form_field_binding(section_id, display_order);

create table workops.portal_form_submission (
    id                  uuid    not null default gen_random_uuid() primary key,
    portal_form_id      uuid    not null references workops.portal_form(id) on delete cascade,
    version             integer not null,
    task_id             uuid    not null references workops.task(id) on delete cascade,
    submitted_at        timestamptz not null default now(),
    raw_answers         jsonb   not null default '{}'::jsonb
);

create index portal_form_submission_form_idx on workops.portal_form_submission(portal_form_id, version);
create index portal_form_submission_task_idx on workops.portal_form_submission(task_id);

-- Each request type points at its current active form.
alter table workops.portal_request_type
    add column if not exists portal_form_id uuid;

alter table workops.portal_request_type
    add constraint portal_request_type_form_fk
    foreign key (portal_form_id)
    references workops.portal_form(id) on delete set null;
