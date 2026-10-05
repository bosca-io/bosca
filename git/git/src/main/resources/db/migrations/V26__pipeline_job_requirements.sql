-- Required-artifact declarations (GIT-SPEC-5): a job's YAML can declare the registry artifacts that
-- must exist before it may dispatch — the consumption dual of V25's produced-artifact declarations.
-- Stored as a JSON array of ArtifactRequirement (coordinates resolved at run creation).
--
-- The claim path stays pure SQL: an async checker (registry-publish event listener + sweep) verifies
-- requirements against the artifact registry and stamps requirements_satisfied_at; the agent claim
-- query dispatches a job only when it has no requirements or the stamp is set. requirements_deadline
-- is the earliest per-requirement timeout expiry — a job still unsatisfied past it fails loudly.
alter table git.pipeline_jobs
    add column requirements              jsonb not null default '[]'::jsonb,
    add column requirements_satisfied_at timestamptz,
    add column requirements_deadline     timestamptz;

-- The checker's working set: queued jobs with unmet requirements.
create index pipeline_jobs_awaiting_requirements_idx
    on git.pipeline_jobs (created)
    where status = 'queued' and requirements_satisfied_at is null and requirements != '[]'::jsonb;
