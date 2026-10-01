-- Work Ops — V49: Environment key + entity permissions (GIT-SPEC-6).
--
-- Release-pipeline YAML is the source of truth for environment topology and links to WorkOps
-- environments by a program-unique KEY; WorkOps owns the display name. Environment-targeting
-- actions (approve, deploy) check per-environment permission rows exactly like a Metadata, so
-- environments get the standard PermissibleEntity columns and a permissions table.

-- 1. Key column: backfill from the name (slugified), suffixing duplicates within a program.

alter table workops.environment
    add column if not exists key text;

with ranked as (
    select id,
           trim(both '-' from lower(regexp_replace(name, '[^A-Za-z0-9]+', '-', 'g'))) as slug,
           row_number() over (
               partition by program_id, trim(both '-' from lower(regexp_replace(name, '[^A-Za-z0-9]+', '-', 'g')))
               order by display_order, id
           ) as rn
    from workops.environment
)
update workops.environment e
set key = ranked.slug || case when ranked.rn > 1 then '-' || ranked.rn else '' end
from ranked
where ranked.id = e.id
  and e.key is null;

alter table workops.environment
    alter column key set not null;

create unique index if not exists environment_program_key_idx
    on workops.environment (program_id, key);

-- 2. PermissibleEntity columns.

alter table workops.environment
    add column if not exists public                boolean not null default false,
    add column if not exists public_content        boolean not null default false,
    add column if not exists public_list           boolean not null default false,
    add column if not exists public_supplementary  boolean not null default false;

-- 3. Per-entity permission table (same shape as program_permissions).

create table if not exists workops.environment_permissions (
    environment_id  uuid              not null references workops.environment(id) on delete cascade,
    group_id        uuid              not null,
    action          permission_action not null,
    primary key (environment_id, group_id, action)
);
