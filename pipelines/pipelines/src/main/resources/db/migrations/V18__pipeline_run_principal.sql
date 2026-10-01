-- Carry the originating principal of an on-demand (manual / API) run so the run executes under the
-- caller's security context end to end. An on-demand run is driven asynchronously by its own run job
-- (so a suspending node has a job to resume from); without the captured principal that driving job —
-- and the run's backing work and resumes — would fall back to the service account, dropping the
-- caller's authorization. Nullable: a triggered/scheduled run has no originating caller and drives
-- under the pipelines service account.
alter table pipelines.pipeline_run
    add column principal_id uuid;
