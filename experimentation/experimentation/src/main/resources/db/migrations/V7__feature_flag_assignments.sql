-- Replace evaluation-activity snapshots with current assignment state.
--
-- flag_exposures.last_seen_at was refreshed on every evaluation, so neither
-- timestamp in that table represented when the current variation was assigned.
-- The old rows cannot be migrated truthfully. Drop the snapshot and allow the
-- assignment table to repopulate as identified subjects evaluate enabled flags.
drop table experimentation.flag_exposures;

create table experimentation.flag_assignments (
    id              uuid primary key default gen_random_uuid(),
    flag_id         uuid not null references experimentation.feature_flags(id) on delete cascade,
    variation_key   varchar not null,
    principal_id    uuid,
    installation_id varchar not null,
    assigned_at     timestamptz not null
);

create unique index idx_flag_assignments_flag_installation
    on experimentation.flag_assignments (flag_id, installation_id);
create index idx_flag_assignments_flag_principal
    on experimentation.flag_assignments (flag_id, principal_id)
    where principal_id is not null;
create index idx_flag_assignments_flag_variation
    on experimentation.flag_assignments (flag_id, variation_key);
