-- Work Ops — set the document template + `type` on existing Spec & Requirement docs.
--
-- Corrects V33, which keyed its backfill off documents.template_metadata_id.
-- Spec/Requirement documents do NOT record their template there — the authoritative
-- link from a document to its WorkOps concept is the workops.spec / workops.requirement
-- table (each row's metadata_id points at the document's Metadata). As a result the
-- content layer never merged the template `default_attributes` (the `type`) onto the
-- documents, and many documents have no template_metadata_id set at all.
--
-- This migration, scoping by the workops tables and resolving the templates BY NAME
-- (so it is independent of the template metadata UUID, which can differ between
-- environments):
--   A. Ensures each template carries its `type` in default_attributes (idempotent).
--   B. Sets the template link on existing Spec/Requirement `documents` rows that lack
--      one. (Documents that do not yet exist are left alone — they get their template
--      when first created/edited, now handled by the service creation path.)
--   C. Backfills metadata.attributes.type for every Spec/Requirement, taking the value
--      from the respective template's default_attributes (single source of truth).
--
--   "Spec Document Template"        -> type "WorkOps Spec"
--   "Requirement Document Template" -> type "WorkOps Requirement"
-- (`attributes.type` is the JSON attribute key, distinct from the metadata.type enum.)

-- A. Ensure the templates carry `type` (insurance; preserves any other keys).
update document_templates dt
set default_attributes = coalesce(dt.default_attributes, '{}'::jsonb)
                         || jsonb_build_object('type', 'WorkOps Spec')
from metadata tm
where tm.id = dt.metadata_id
  and tm.content_type = 'bosca/v-document-template'
  and tm.name = 'Spec Document Template'
  and not (coalesce(dt.default_attributes, '{}'::jsonb) ? 'type');

update document_templates dt
set default_attributes = coalesce(dt.default_attributes, '{}'::jsonb)
                         || jsonb_build_object('type', 'WorkOps Requirement')
from metadata tm
where tm.id = dt.metadata_id
  and tm.content_type = 'bosca/v-document-template'
  and tm.name = 'Requirement Document Template'
  and not (coalesce(dt.default_attributes, '{}'::jsonb) ? 'type');

-- B. Set the template link on existing Spec documents that have a documents row.
update documents d
set template_metadata_id      = t.template_id,
    template_metadata_version = t.template_version
from (
         select dt.metadata_id as template_id, dt.version as template_version
         from document_templates dt
                  join metadata tm on tm.id = dt.metadata_id
         where tm.content_type = 'bosca/v-document-template'
           and tm.name = 'Spec Document Template'
         order by dt.version desc
         limit 1
     ) t
where d.metadata_id in (select metadata_id from workops.spec where metadata_id is not null)
  and d.template_metadata_id is null;

-- B. (Requirements)
update documents d
set template_metadata_id      = t.template_id,
    template_metadata_version = t.template_version
from (
         select dt.metadata_id as template_id, dt.version as template_version
         from document_templates dt
                  join metadata tm on tm.id = dt.metadata_id
         where tm.content_type = 'bosca/v-document-template'
           and tm.name = 'Requirement Document Template'
         order by dt.version desc
         limit 1
     ) t
where d.metadata_id in (select metadata_id from workops.requirement where metadata_id is not null)
  and d.template_metadata_id is null;

-- C. Backfill the `type` attribute on every Spec, from the Spec template.
update metadata m
set attributes = coalesce(m.attributes, '{}'::jsonb)
                 || jsonb_build_object('type', t.type_val)
from (
         select dt.default_attributes -> 'type' as type_val
         from document_templates dt
                  join metadata tm on tm.id = dt.metadata_id
         where tm.content_type = 'bosca/v-document-template'
           and tm.name = 'Spec Document Template'
           and dt.default_attributes ? 'type'
         order by dt.version desc
         limit 1
     ) t
where m.id in (select metadata_id from workops.spec where metadata_id is not null)
  and not (coalesce(m.attributes, '{}'::jsonb) ? 'type');

-- C. (Requirements)
update metadata m
set attributes = coalesce(m.attributes, '{}'::jsonb)
                 || jsonb_build_object('type', t.type_val)
from (
         select dt.default_attributes -> 'type' as type_val
         from document_templates dt
                  join metadata tm on tm.id = dt.metadata_id
         where tm.content_type = 'bosca/v-document-template'
           and tm.name = 'Requirement Document Template'
           and dt.default_attributes ? 'type'
         order by dt.version desc
         limit 1
     ) t
where m.id in (select metadata_id from workops.requirement where metadata_id is not null)
  and not (coalesce(m.attributes, '{}'::jsonb) ? 'type');
