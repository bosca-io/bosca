-- WORKOPS-SPEC-23 — environment-scoped artifacts. A build can publish per-environment artifacts (e.g.
-- one Helm values file per environment); the publication records the registry namespace it lives in and
-- the environments it serves, so a deploy target can pick the right publication for its environment.
alter table workops.artifact_publication
    add column namespace    text,
    add column environments text[] not null default '{}'::text[];
