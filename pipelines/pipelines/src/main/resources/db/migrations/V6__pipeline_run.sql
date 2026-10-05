-- Durable, mutable run state — the row a pipeline run is rebuilt from when it resumes after
-- suspending. Distinct from pipeline_run_log (the append-only one-row-per-finished-run audit, kept
-- unchanged): pipeline_run is the LIVE record, updated as the run progresses.

create type pipelines.pipeline_run_status as enum ('running', 'suspended', 'ok', 'failed', 'cancelled');

create table pipelines.pipeline_run
(
    id             uuid                          not null default gen_random_uuid() primary key,
    pipeline_id    uuid                          not null references pipelines.pipelines (id) on delete cascade,
    status         pipelines.pipeline_run_status not null default 'running',
    event_name     text                          not null default '',
    -- The graph as it was at run start; resumption evaluates THIS, never the (possibly edited) live pipeline.
    graph_snapshot jsonb                         not null,
    -- Seed value encoded to JSON + its origin type's serial name, to reconstruct the InputNode value on resume.
    input          jsonb,
    input_type     text,
    -- Checkpoint: nodeId -> output encoded to JSON (JSON null = node produced no output).
    node_outputs   jsonb                         not null default '{}'::jsonb,
    -- Set of {nodeId, correlationId} the run is parked on while suspended.
    awaiting       jsonb                         not null default '[]'::jsonb,
    error          text,
    version        bigint                        not null default 0,
    created_at     timestamptz                   not null default now(),
    modified_at    timestamptz                   not null default now(),
    deleted_at     timestamptz
);

-- Most lookups are "live runs for a pipeline, newest first"; the partial index skips soft-deleted rows.
create index ix_pipeline_run_pipeline
    on pipelines.pipeline_run (pipeline_id, created_at desc)
    where deleted_at is null;

-- The resume sweeper (later phases) scans for non-terminal runs; keep that scan cheap.
create index ix_pipeline_run_active
    on pipelines.pipeline_run (status, modified_at)
    where deleted_at is null and status in ('running', 'suspended');
