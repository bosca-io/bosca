-- Work Ops — Phase 1 (specs/workops/plan.md §1.2)
--
-- Establishes the dedicated `workops` Postgres schema that owns every table
-- introduced by the Work Ops subsystem (specs/workops/requirements.md §
-- "Implementation Decisions / Database schema and migrations"). Subsequent
-- phases populate this schema with the hierarchy, task core, workflow,
-- board, sprint, BQL, automation, SLA, OKR, audit, and integration tables.
--
-- The schema is isolated from `public` so cross-schema reads stay readable
-- and so the application role's permissions on Work Ops tables can diverge
-- from its `public` permissions later (notably: append-only `task_history`
-- in Phase 2 strips UPDATE/DELETE grants on the application role).

create schema if not exists workops;
