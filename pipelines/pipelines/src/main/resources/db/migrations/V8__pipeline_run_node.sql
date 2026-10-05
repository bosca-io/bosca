-- Per-node execution timeline for a durable run (WORKOPS-SPEC-13 / REQ-121) — the observability
-- record behind run-detail (REQ-122) and per-node metrics (REQ-124). Append-only: one row per node
-- execution EVENT, so a suspendable node shows its full lifecycle (e.g. a 'suspended' row when it
-- parks, then an 'ok' row when its resume completes it). Distinct from pipeline_run.node_outputs
-- (the live checkpoint) and pipeline_run_log (the per-run audit).

create type pipelines.node_execution_status as enum ('ok', 'failed', 'skipped', 'suspended');

create table pipelines.pipeline_run_node
(
    id          uuid                            not null default gen_random_uuid() primary key,
    run_id      uuid                            not null references pipelines.pipeline_run (id) on delete cascade,
    node_id     text                            not null,
    status      pipelines.node_execution_status not null,
    started_at  timestamptz                     not null,
    finished_at timestamptz                     not null,
    duration_ms bigint                          not null default 0,
    -- The output port the value left on (routing/error port), or null for the implicit output.
    port        text,
    error       text,
    -- Size-bounded JSON snapshot of the node's output (null when none, skipped, or capped).
    output      jsonb,
    created_at  timestamptz                     not null default now()
);

-- The run-detail timeline reads every event for one run in execution order.
create index ix_pipeline_run_node_run on pipelines.pipeline_run_node (run_id, started_at);
