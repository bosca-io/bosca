create table metadata_workflow_plans
(
    id      uuid                     not null,
    plan_id varchar                  not null,
    queue   varchar                  not null,
    created timestamp with time zone not null default now(),
    primary key (id, plan_id),
    foreign key (id) references metadata (id) on delete cascade
);

create table collection_workflow_plans
(
    id      uuid                     not null,
    plan_id varchar                  not null,
    queue   varchar                  not null,
    created timestamp with time zone not null default now(),
    primary key (id, plan_id),
    foreign key (id) references collections (id) on delete cascade
);
