-- Deferred job conditions (GIT-SPEC-6 failure routing): a job whose `if:` references needs.* or
-- uses always()/failure()/cancelled() cannot be decided at run creation — its condition is stored
-- here and evaluated server-side once every dependency is terminal. True stamps
-- condition_satisfied_at (the job becomes claimable even over failed dependencies — that is the
-- point of failure routing); false marks the job SKIPPED. Ordinary jobs store null (their
-- conditions were consumed at creation) and keep the all-dependencies-succeeded claim rule.
alter table git.pipeline_jobs
    add column condition              text,
    add column condition_satisfied_at timestamptz;
