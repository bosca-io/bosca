-- A pipeline key identifies ONE pipeline (installers upsert by it; REST endpoints route by it) — but
-- nothing enforced that, and the server and runner running package installers concurrently at boot
-- each inserted their own copy (four live "release-relay" rows, every one triggered, every release
-- start firing all of them). Soft-delete all but the newest per key, then guarantee uniqueness.
update pipelines.pipelines p
set deleted_at = now()
where p.deleted_at is null
  and p.key <> ''
  and exists (
      select 1 from pipelines.pipelines newer
      where newer.key = p.key
        and newer.deleted_at is null
        and newer.created_at > p.created_at
  );

create unique index pipelines_active_key_idx
    on pipelines.pipelines (key)
    where deleted_at is null and key <> '';
