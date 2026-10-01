-- Work Ops — Phase 13 (specs/workops/plan.md §13, R33)
--
-- Opt-in flags only — the actual AI bindings ship in
-- `core-ai` and Phase 23 (RAG). Phase 13 records the
-- per-org / per-project / per-task opt-in so the AI services
-- short-circuit without making an external call when any layer
-- declines.

alter table workops.project
    add column if not exists ai_opt_in boolean not null default true;

alter table workops.task
    add column if not exists ai_opt_in boolean;

-- Single-row org-level opt-in. Identified by the well-known
-- id 00000000-0000-0000-0000-000000000001 to keep upserts
-- deterministic without an external sequence.
create table workops.ai_org_settings (
    id              uuid    not null primary key default '00000000-0000-0000-0000-000000000001',
    enabled         boolean not null default true,
    last_modified   timestamptz not null default now()
);

insert into workops.ai_org_settings (id, enabled)
values ('00000000-0000-0000-0000-000000000001', true)
on conflict (id) do nothing;
