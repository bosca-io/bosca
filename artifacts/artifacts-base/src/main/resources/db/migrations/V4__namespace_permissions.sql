create table artifacts.namespace_permissions (
    namespace_id   uuid not null references artifacts.namespaces(id) on delete cascade,
    group_id       uuid not null references groups(id) on delete cascade,
    action         permission_action not null,
    primary key (namespace_id, group_id, action)
);
create index idx_artifact_ns_perms_group on artifacts.namespace_permissions (group_id);
