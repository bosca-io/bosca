-- Saga rollback log (WORKOPS-SPEC-16 / REQ-131): one row per node that completed successfully and
-- declares a rollback pipeline, recording the rollback pipeline to run and the node's output to feed
-- it. On a later non-OK terminal, the run service replays these in reverse `seq` order to undo effects.
create table pipelines.pipeline_run_rollback
(
    seq                  bigserial   not null primary key,
    run_id               uuid        not null,
    node_id              text        not null,
    rollback_pipeline_id uuid        not null,
    output               jsonb,
    created_at           timestamptz not null default now()
);

create index pipeline_run_rollback_run_idx on pipelines.pipeline_run_rollback (run_id, seq);
