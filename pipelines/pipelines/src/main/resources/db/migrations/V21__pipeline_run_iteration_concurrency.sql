-- Bounded durable iteration (WORKOPS-SPEC-12 / REQ-119 follow-up). The ForEach node's maxConcurrency
-- was only honored on the inline (non-durable) path — a durable run enqueued every child at once, so
-- a "sequential" ForEach (the release relay's dependency-ordered builds) actually fanned out fully
-- parallel. The iteration row now carries the bound and the raw item payloads so children can start
-- lazily: the first N at suspend time, then item i+N as item i reports its result.
alter table pipelines.pipeline_run_iteration
    add column max_concurrency int not null default 0, -- 0 = unbounded (legacy rows keep today's behavior)
    add column items           jsonb;                  -- the ForEach's item array; null when unbounded (children pre-created)

-- One child run per (parent, node, item): makes the lazy child start idempotent under at-least-once
-- redelivery of a sibling's completion report (a conflicting insert re-enqueues the existing child).
-- Defensively collapse any historical duplicates first (suspend redelivery could double-create
-- children before this constraint existed) — the earliest row per key wins; the aggregation always
-- keyed results by index, so dropping a duplicate loses nothing.
delete from pipelines.pipeline_run pr
 where pr.parent_run_id is not null and pr.parent_node_id is not null and pr.item_index is not null
   and exists (
       select 1
         from pipelines.pipeline_run dup
        where dup.parent_run_id = pr.parent_run_id
          and dup.parent_node_id = pr.parent_node_id
          and dup.item_index = pr.item_index
          and (dup.created_at, dup.id) < (pr.created_at, pr.id)
   );

create unique index pipeline_run_parent_item_idx
    on pipelines.pipeline_run (parent_run_id, parent_node_id, item_index)
    where parent_run_id is not null and parent_node_id is not null and item_index is not null;
