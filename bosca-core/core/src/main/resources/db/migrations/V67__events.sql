create table events
(
    type        varchar not null,
    workflow_id varchar not null,
    primary key (type, workflow_id),
    foreign key (workflow_id) references workflows (id)
);