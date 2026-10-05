-- Feeds — "Feed Item" document template seed
--
-- Every ingested feed item's document is bound to this template, whose default
-- attributes stamp `type: "Feed Item"` onto the item (merged by
-- MetadataService.setDocumentTemplate on every ingest). Follows the workops
-- template-seed precedent (V26__spec_document_templates.sql): the template is a
-- content-system metadata item referenced by document_templates (metadata_id, version);
-- this module's migration owns it because the template is feeds-domain-specific.
--
-- Stable well-known UUID: f0000000-0000-0000-0000-000000000001
--
-- The content tables live in the public schema (this migration runs with `feeds` as the
-- connection default, so they are qualified). The whole seed is guarded so a feeds-only
-- database — the repository integration tests run the feeds migrations against a bare
-- container without the content module — skips it; every real install has the tables.

do $$
begin
    if to_regclass('public.metadata') is null or to_regclass('public.document_templates') is null then
        return;
    end if;

    insert into public.metadata (id, name, content_type, language_tag, workflow_state_id)
    values
        ('f0000000-0000-0000-0000-000000000001', 'Feed Item Template', 'bosca/v-document-template', 'en', 'published')
    on conflict (id) do nothing;

    insert into public.document_templates (metadata_id, version, configuration, schema, content, default_attributes)
    values
        (
            'f0000000-0000-0000-0000-000000000001', 1, null, null,
            '{
                "document": {
                    "type": "doc",
                    "content": [
                        {"type": "paragraph", "content": []}
                    ]
                }
            }'::jsonb,
            '{"type": "Feed Item"}'::jsonb
        )
    on conflict do nothing;
end
$$;
