alter table form_schemas add column configuration jsonb;

alter table form_submissions drop constraint form_submissions_form_template_id_form_template_version_fkey;
drop index idx_form_submissions_template;
alter table form_submissions drop column form_template_id;
alter table form_submissions drop column form_template_version;
alter table form_submissions add column form_schema_id uuid not null references form_schemas(id);
create index idx_form_submissions_form_schema on form_submissions(form_schema_id);
