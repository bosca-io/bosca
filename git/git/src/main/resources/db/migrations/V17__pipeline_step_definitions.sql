-- Persist the full StepDefinition on each pipeline_step and the relevant
-- JobDefinition fields on each pipeline_job so agents claiming a job
-- receive everything needed to execute. Prior to this, only name + ordinal
-- were stored on steps, forcing agents to re-parse the pipeline YAML from
-- the checked-out source — which fails for the Checkout step itself
-- (chicken-and-egg) and silently dropped fields like `with` along the way.
--
-- `with` is a reserved keyword in SQL, so the column is named `with_args`.

alter table git.pipeline_jobs
    add column timeout_minutes int;

alter table git.pipeline_steps
    add column uses              varchar,
    add column run               text,
    add column image             varchar,
    add column condition         text,
    add column working_directory varchar,
    add column with_args         jsonb not null default '{}'::jsonb,
    add column env               jsonb not null default '{}'::jsonb;
