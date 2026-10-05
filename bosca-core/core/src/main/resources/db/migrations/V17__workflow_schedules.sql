create table workflow_schedules
(
    id             uuid                     not null default gen_random_uuid(),
    metadata_id    uuid,
    collection_id  uuid,
    workflow_id    varchar                  not null,
    attributes     jsonb,
    configuration  jsonb,
    rrule          varchar                  not null,
    starts         timestamp with time zone not null,
    ends           timestamp with time zone not null,
    last_scheduled timestamp with time zone not null,
    last_run       timestamp with time zone not null,
    next_run       timestamp with time zone not null,
    enabled        boolean                  not null default true,
    primary key (id, workflow_id),
    foreign key (metadata_id) references metadata (id) on delete cascade,
    foreign key (collection_id) references collections (id) on delete cascade,
    foreign key (workflow_id) references workflows (id) on delete cascade
);
