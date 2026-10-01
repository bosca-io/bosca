-- Work Ops — V27: Permission tables for specs and requirements

create table workops.spec_permissions (
    spec_id   uuid              not null references workops.spec(id) on delete cascade,
    group_id  uuid              not null,
    action    permission_action not null,
    primary key (spec_id, group_id, action)
);

create table workops.requirement_permissions (
    requirement_id  uuid              not null references workops.requirement(id) on delete cascade,
    group_id        uuid              not null,
    action          permission_action not null,
    primary key (requirement_id, group_id, action)
);
