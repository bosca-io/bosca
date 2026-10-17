create table backup.object_store_writers (
    storage_key text primary key references backup.object_stores(key),
    run_key varchar(64) not null references backup.object_runs(key)
);

create index object_store_writers_run on backup.object_store_writers(run_key);

insert into backup.object_store_writers (storage_key, run_key)
select s.backup_key, r.key
from backup.object_runs r
join backup.object_run_storage s on s.key = r.key
where r.status = 'running' and r.operation = 'backup'
union
select s.storage_key, r.key
from backup.object_runs r
join backup.object_run_stores s on s.run_key = r.key
where r.status = 'running' and r.operation = 'restore';
