ALTER TABLE collection_language_variants
    ADD COLUMN ready TIMESTAMP WITH TIME ZONE;

-- Change existing variants in 'draft' state to 'pending' for consistency with the new default
UPDATE collection_language_variants SET workflow_state_id = 'pending' WHERE workflow_state_id = 'draft';
