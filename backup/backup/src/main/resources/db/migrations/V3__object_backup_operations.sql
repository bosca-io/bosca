alter table backup.object_progress
    add column operation text not null default 'backup'
        check (operation in ('backup', 'restore'));

alter table backup.object_runs
    add column operation text not null default 'backup'
        check (operation in ('backup', 'restore'));

drop index backup.object_progress_scope;
create index object_progress_scope
    on backup.object_progress (source_id, destination_id, scope, operation, created, key);

drop index backup.object_runs_scope;
create index object_runs_scope
    on backup.object_runs (source_id, destination_id, scope, operation, created desc, key);
