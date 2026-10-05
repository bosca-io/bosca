-- No-op: the identity constraint (principal_id IS NOT NULL OR
-- installation_id IS NOT NULL) is already enforced by
-- V1__experimentation.sql as `assignments_identity_present`.
-- This migration exists only as a placeholder so the Flyway version
-- sequence remains contiguous after V3.
SELECT 1;
