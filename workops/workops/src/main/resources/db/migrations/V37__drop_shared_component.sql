-- V37: Remove the unwired SharedComponent scaffolding (WORKOPS-SPEC-22 R26).
--
-- SharedComponent / SharedComponentParticipation were CRUD-only — no behavior was ever wired to them
-- (no assignment routing, no cross-project rollup, no UI) — and "Component" already means several other
-- things in WorkOps. The model, service, repository, and GraphQL surface were removed; drop the tables.
-- Participation first (it FKs shared_component).

drop table if exists workops.shared_component_participation;
drop table if exists workops.shared_component;
