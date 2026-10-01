-- An explicit, attributable escape hatch for a single job's external artifact/upstream-pipeline
-- requirements. requirements_satisfied_at remains the claim-SQL gate; these columns distinguish a
-- user override from a checker-verified requirement so the exception remains visible afterward.
alter table git.pipeline_jobs
    add column requirements_bypassed_at timestamptz,
    add column requirements_bypassed_by uuid,
    add column requirements_bypass_reason text;
