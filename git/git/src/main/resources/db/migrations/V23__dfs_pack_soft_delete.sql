-- Soft-delete marker for DFS packs.
--
-- Garbage collection and thin-pack compaction replace a set of packs with a new
-- consolidated pack. Physically deleting the replaced packs' object-storage
-- files inline is unsafe for two reasons:
--   1. It is not atomic with the metadata swap. The old path deleted the S3
--      object and then the metadata row as independent statements on a bare
--      (auto-commit) connection, so a crash or error between them left a
--      committed row pointing at an object that no longer exists -> the pack
--      reads back as corrupt.
--   2. It can pull a pack out from under a concurrent clone/fetch that listed
--      the pack before the swap and is still streaming it from storage.
--
-- Instead, replaced packs are marked with `deleted_at` inside the same
-- transaction that commits their replacement, and a background reaper physically
-- removes the object-storage files only after a grace window during which any
-- in-flight read has completed. See ObjectStorageDfsStorageAdapter.commitPacks
-- and RepositoryLifecycleServiceImpl.reapDeletedPacks.
alter table git.dfs_packs
    add column deleted_at timestamptz;

-- listPacks() must ignore soft-deleted packs. Replace the partial index so it
-- stays selective for the hot "live committed packs" lookup.
drop index if exists git.idx_git_dfs_packs_repo;
create index idx_git_dfs_packs_repo
    on git.dfs_packs (repository_id)
    where committed = true and deleted_at is null;

-- Reaper scan: find soft-deleted packs whose grace window has elapsed.
create index idx_git_dfs_packs_reap
    on git.dfs_packs (deleted_at)
    where deleted_at is not null;
