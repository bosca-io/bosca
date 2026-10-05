-- Soft-delete support for principals and profiles. A non-null `deleted_at` marks the row as
-- staged for deletion (reversible) ahead of a full, irreversible hard delete. The partial
-- indexes back the "live rows only" lookups (the default list/auth paths) without indexing the
-- soft-deleted minority.
alter table principals add column deleted_at timestamp with time zone;
alter table profiles add column deleted_at timestamp with time zone;

create index ix_principals_deleted_at on principals (id) where deleted_at is null;
create index ix_profiles_deleted_at on profiles (id) where deleted_at is null;
