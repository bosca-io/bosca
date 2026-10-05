-- Work Ops — Remove redundant portal form tables.
--
-- Portal request types now reference Bosca Forms (core-forms)
-- via form_schema_key instead of the parallel portal_form
-- builder that duplicated core-forms capabilities.

-- Drop submission audit first (references portal_form).
drop table if exists workops.portal_form_submission;

-- Drop field bindings (references portal_form_section).
drop table if exists workops.portal_form_field_binding;

-- Drop sections (references portal_form).
drop table if exists workops.portal_form_section;

-- Remove the FK pointer from request_type before dropping portal_form.
alter table workops.portal_request_type
    drop column if exists portal_form_id;

-- Drop the portal_form table itself.
drop table if exists workops.portal_form;

-- Add form_schema_key to portal_request_type, referencing a
-- core-forms FormSchema by its string key.
alter table workops.portal_request_type
    add column if not exists form_schema_key varchar;
