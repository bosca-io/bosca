create table if not exists workflow_events
(
    event_name  varchar(255) not null,
    workflow_id varchar(255) not null,
    primary key (event_name, workflow_id),
    foreign key (workflow_id) references workflows (id) on delete cascade
)
