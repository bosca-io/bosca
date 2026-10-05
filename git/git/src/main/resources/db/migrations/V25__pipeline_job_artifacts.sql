-- Produced-artifact declarations (WORKOPS-SPEC-23): a job's YAML can declare the artifacts it
-- produced (type + namespace + type-specific coordinate) so the run reports them and a pipeline node
-- can query what's available. Stored as a JSON array of ArtifactDefinition on the job.
alter table git.pipeline_jobs add column artifacts jsonb not null default '[]'::jsonb;
