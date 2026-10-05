ALTER TABLE collection_language_variants
    ADD COLUMN workflow_state_id VARCHAR NOT NULL DEFAULT 'draft',
    ADD COLUMN workflow_state_pending_id VARCHAR,
    ADD COLUMN workflow_state_valid TIMESTAMP WITH TIME ZONE,
    ADD COLUMN delete_workflow_id VARCHAR;

-- Backfill existing variants with "draft" state (the default handles this, but be explicit)
UPDATE collection_language_variants SET workflow_state_id = 'draft' WHERE workflow_state_id IS NULL;

-- Remove the default so future inserts must provide a value explicitly
ALTER TABLE collection_language_variants ALTER COLUMN workflow_state_id DROP DEFAULT;
