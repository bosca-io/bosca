-- The releases list orders newest-first, which needs a creation timestamp — the release table never
-- had one. Existing rows backfill to now() (ordering among them was undefined before anyway).
alter table workops.release add column created timestamptz not null default now();
