-- Environments + approvals (GIT-SPEC-6): a job may bind to an environment KEY (declared in the
-- release pipeline's environments: block) and may require human approval before dispatch — its own
-- `approval: true` or the bound environment's policy, resolved at run creation. Approval is a gate
-- stamp like requirements_satisfied_at/condition_satisfied_at: the claim query skips an
-- approval-required job until approved_at is set; rejection fails the job outright. The
-- "awaiting approval" state is derived: queued + approval_required + unapproved + other gates clear.
alter table git.pipeline_jobs
    add column environment       text,
    add column approval_required boolean not null default false,
    add column approved_at       timestamptz,
    add column approved_by       uuid,
    add column approval_comment  text;
