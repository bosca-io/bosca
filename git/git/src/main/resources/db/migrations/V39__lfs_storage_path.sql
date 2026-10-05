alter table git.lfs_objects add column storage_path varchar;
update git.lfs_objects set storage_path = 'git-lfs/' || repository_id || '/' || oid;
alter table git.lfs_objects alter column storage_path set not null;
