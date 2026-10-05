alter table scripting.scripts
    add column deleted_at timestamp with time zone;

create index idx_scripts_deleted_at on scripting.scripts (deleted_at)
    where deleted_at is not null;

drop index scripting.idx_scripts_type;
create index idx_scripts_type on scripting.scripts (type)
    where enabled = true and deleted_at is null;
