alter table scripting.scripts add column public boolean not null default false;

create table scripting.script_permissions
(
    script_id uuid              not null references scripting.scripts (id) on delete cascade,
    group_id  uuid              not null references groups (id) on delete cascade,
    action    permission_action not null,
    primary key (script_id, group_id, action)
);

create index idx_script_permissions_script on scripting.script_permissions (script_id);
