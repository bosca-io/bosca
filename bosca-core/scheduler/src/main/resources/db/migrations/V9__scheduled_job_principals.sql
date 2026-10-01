-- Optional execution identities for principal-aware cron jobs.
-- Existing infrastructure jobs remain explicitly not_required.

create type scheduler.scheduled_job_principal_state as enum (
    'not_required',
    'needs_principal',
    'pending_confirmation',
    'active'
);

alter table scheduler.scheduled_jobs
    add column execution_principal_id uuid,
    add column principal_state scheduler.scheduled_job_principal_state not null default 'not_required',
    add column principal_assigned_by uuid,
    add column principal_confirmed_by uuid,
    add constraint ck_scheduled_job_principal_state check (
        (
            principal_state = 'not_required'
            and execution_principal_id is null
            and principal_assigned_by is null
            and principal_confirmed_by is null
        )
        or (
            principal_state = 'needs_principal'
            and principal_confirmed_by is null
            and enabled = false
        )
        or (
            principal_state = 'pending_confirmation'
            and execution_principal_id is not null
            and principal_assigned_by is not null
            and principal_confirmed_by is null
            and enabled = false
        )
        or (
            principal_state = 'active'
            and execution_principal_id is not null
            and principal_assigned_by is not null
            and principal_confirmed_by is not null
        )
    );

create index ix_scheduled_jobs_principal_state
    on scheduler.scheduled_jobs (principal_state);
