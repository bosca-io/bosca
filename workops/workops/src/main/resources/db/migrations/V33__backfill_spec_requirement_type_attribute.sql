-- Work Ops — backfill the `type` attribute on Spec & Requirement documents.
--
-- Spec and Requirement documents are Metadata projections created from the
-- document templates seeded in V26. The content layer copies a template's
-- `default_attributes` onto `metadata.attributes` when the template is applied
-- (see MetadataServiceImpl.setDocumentTemplate / addDocument), so a `type`
-- attribute is meant to flow from the template down to every document.
--
-- V26 seeded both templates with `default_attributes = null`, so existing
-- Spec/Requirement documents were created without a `type`. This migration:
--   1. Seeds `type` onto each template's `default_attributes` so newly created
--      documents inherit it going forward (and so already-migrated databases
--      converge — editing V26 would not re-run on them).
--   2. Backfills existing documents, taking the value FROM each document's
--      respective template `default_attributes` rather than hardcoding it in the
--      UPDATE, so the template stays the single source of truth.
--
-- The templates are identified by NAME (+ the document-template content type),
-- not by their seeded UUIDs: the metadata IDs may differ between environments
-- (e.g. production), but the template names are stable.
--   "Spec Document Template"        -> type "WorkOps Spec"
--   "Requirement Document Template" -> type "WorkOps Requirement"
-- The `attributes.type` referenced here is the JSON attribute key, which is
-- distinct from the `metadata.type` enum column.

-- 1. Seed the `type` onto the template default_attributes (preserve any other keys).
update document_templates dt
set default_attributes = coalesce(dt.default_attributes, '{}'::jsonb)
                         || jsonb_build_object('type', 'WorkOps Spec')
from metadata tm
where tm.id = dt.metadata_id
  and tm.content_type = 'bosca/v-document-template'
  and tm.name = 'Spec Document Template';

update document_templates dt
set default_attributes = coalesce(dt.default_attributes, '{}'::jsonb)
                         || jsonb_build_object('type', 'WorkOps Requirement')
from metadata tm
where tm.id = dt.metadata_id
  and tm.content_type = 'bosca/v-document-template'
  and tm.name = 'Requirement Document Template';

-- 2. Backfill existing documents that were created from these templates and do
--    not already carry a `type`. The value is read from the document's own
--    template default_attributes so spec and requirement docs each get the
--    correct type, and any document already carrying a `type` is left untouched.
update metadata m
set attributes = coalesce(m.attributes, '{}'::jsonb)
                 || jsonb_build_object('type', dt.default_attributes -> 'type')
from documents d
         join document_templates dt
              on dt.metadata_id = d.template_metadata_id
                  and dt.version = d.template_metadata_version
         join metadata tm
              on tm.id = dt.metadata_id
where d.metadata_id = m.id
  and tm.content_type = 'bosca/v-document-template'
  and tm.name in ('Spec Document Template', 'Requirement Document Template')
  and dt.default_attributes ? 'type'
  and not (coalesce(m.attributes, '{}'::jsonb) ? 'type');
