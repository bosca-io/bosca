-- WORKOPS-SPEC-22/23: soft delete for releases. A deleted release is hidden from reads (getById /
-- listForProgram filter deleted_at is null) but its row and cascaded bundle rows are retained, so a
-- delete is recoverable rather than destructive.
alter table workops.release add column deleted_at timestamptz;

create index release_program_active_idx on workops.release (program_id) where deleted_at is null;
