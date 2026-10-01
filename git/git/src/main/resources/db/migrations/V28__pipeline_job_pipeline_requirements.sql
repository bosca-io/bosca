-- Pipeline-completion requirements (GIT-SPEC-6): a job's YAML `requires:` can also name upstream
-- PIPELINES whose run for the same ref must have SUCCEEDED before the job may dispatch — the
-- ordering the artifact gate (V26) cannot express, because upstream pipelines do load-bearing work
-- after their artifacts publish (version pin bumps, submodule pushes). Stored as a JSON array of
-- PipelineRequirement (correlation refs resolved at run creation).
--
-- Both requirement kinds share the V26 gate: the checker stamps requirements_satisfied_at only when
-- every artifact requirement resolves in the registry AND every pipeline requirement's upstream run
-- finished SUCCESS; the agent claim query dispatches a job only when it has no requirements of
-- either kind or the stamp is set.
alter table git.pipeline_jobs
    add column pipeline_requirements jsonb not null default '[]'::jsonb;

-- The checker's working-set index must cover both requirement kinds.
drop index if exists git.pipeline_jobs_awaiting_requirements_idx;
create index pipeline_jobs_awaiting_requirements_idx
    on git.pipeline_jobs (created)
    where status = 'queued' and requirements_satisfied_at is null
      and (requirements != '[]'::jsonb or pipeline_requirements != '[]'::jsonb);
