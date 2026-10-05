alter table language_resolution_contexts
    drop constraint if exists language_resolution_contexts_fallback_language_tag_fkey;

alter table language_tag_mappings
    drop constraint if exists language_tag_mappings_resolved_language_tag_fkey;
