-- Durable per-item iteration (WORKOPS-SPEC-12 / REQ-119). A ForEach node fans each item out into a
-- child durable run of the body pipeline (so per-item suspend/resume/timeline come from the SPEC-10
-- engine for free), parks the parent on one await, and joins the children's outputs when all finish.

-- Child runs carry their parent linkage; on completion a child reports its output back to the parent.
alter table pipelines.pipeline_run
    add column parent_run_id  uuid,
    add column parent_node_id text,
    add column item_index     int;

-- One aggregation row per ForEach node instance: how many items, the failure policy, and each item's
-- result keyed by index (filled atomically as children finish). When results reach `total`, the last
-- child joins them in index order and signals the parent's ForEach await.
create table pipelines.pipeline_run_iteration
(
    parent_run_id     uuid        not null,
    node_id           text        not null,
    total             int         not null,
    continue_on_error boolean     not null default false,
    results           jsonb       not null default '{}'::jsonb,
    created_at        timestamptz not null default now(),
    primary key (parent_run_id, node_id)
);
