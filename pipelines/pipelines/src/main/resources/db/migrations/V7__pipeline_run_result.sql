-- Deliberate convention deviation (WORKOPS-SPEC-10): pipeline_run is a high-frequency operational
-- state row (a checkpoint write per parked node), so it carries version + soft-delete but NO
-- FieldChange `*_history` table — per-mutation audit would be pure write amplification. The
-- append-only run audit is pipeline_run_log (one immutable row per finished run); the live state is
-- observable directly (Studio "Active Runs"). Same spirit as ecommerce's single-audit-table choice.

-- Staging area for a suspended node's pending output (WORKOPS-SPEC-10). The value a node will emit
-- once its out-of-band work finishes is written here keyed by (run, node) — either by the node at
-- suspend time (a gate stashing its inbound value) or by the backing job (its computed result). On
-- resume the value is promoted into pipeline_run.node_outputs and the staged row removed. Cascades
-- with the run so retention cleans it up.

create table pipelines.pipeline_run_result
(
    run_id     uuid        not null references pipelines.pipeline_run (id) on delete cascade,
    node_id    text        not null,
    result     jsonb       not null,
    created_at timestamptz not null default now(),
    primary key (run_id, node_id)
);
