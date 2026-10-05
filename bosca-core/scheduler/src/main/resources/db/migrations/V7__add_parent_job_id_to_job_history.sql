-- Link fan-out children back to their parent execution so the admin UI can nest
-- multi-job (and other composite) children under their parent rather than showing
-- them as orphaned top-level rows.
ALTER TABLE scheduler.job_history ADD COLUMN parent_job_id UUID;

-- Queries filtering on parent_job_id (nesting children under a parent row in the UI)
-- should not table-scan. Partial index skips the overwhelming majority of rows that
-- have no parent.
CREATE INDEX ix_job_history_parent_job_id
    ON scheduler.job_history (parent_job_id)
    WHERE parent_job_id IS NOT NULL;
