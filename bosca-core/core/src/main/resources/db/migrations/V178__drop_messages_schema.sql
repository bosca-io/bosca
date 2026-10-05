-- Retire the legacy `messages` schema. The messaging domain module was renamed to
-- `communications` and now owns a `communications` schema created by its own migration chain.
-- No data depended on `messages`, so it is dropped outright rather than renamed. Public-schema
-- migrations run before every module migration, so this guarantees the old schema (and its
-- orphaned Flyway history table) is gone before the `communications` chain runs. Idempotent:
-- a harmless no-op on databases that never had the schema.
DROP SCHEMA IF EXISTS messages CASCADE;
