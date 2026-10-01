-- With soft delete (V40), a deleted release still occupied its (program_id, name) slot under the
-- original full unique constraint, so a name could never be reused after deleting a release. Replace it
-- with a PARTIAL unique index that only applies to live rows — deleted releases no longer block the name.
alter table workops.release drop constraint if exists release_program_id_name_key;

create unique index release_program_name_active_idx
    on workops.release (program_id, name)
    where deleted_at is null;
