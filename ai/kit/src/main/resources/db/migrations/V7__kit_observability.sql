alter table kit.runtime_session add column archive_created timestamptz;
alter table kit.runtime_approval add column created timestamptz;
-- Existing approvals and checkpoints have no reliable creation timestamp.
