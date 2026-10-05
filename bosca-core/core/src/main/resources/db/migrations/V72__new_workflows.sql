create table states
(
    id            varchar                   not null,
    name          varchar                   not null,
    description   varchar                   not null,
    type          workflow_state_type       not null,
    configuration jsonb default '{}'::jsonb not null,
    job_name      varchar,
    primary key (id)
);

insert into states (id, name, description, type, configuration)
select id, name, description, type, configuration
from workflow_states;

create table state_transitions
(
    from_state_id  varchar                   not null,
    to_state_id    varchar                   not null,
    description    varchar,
    enter_job_name varchar,
    exit_job_name  varchar,
    configuration  jsonb default '{}'::jsonb not null,
    primary key (from_state_id, to_state_id),
    foreign key (from_state_id) references states (id) on delete cascade,
    foreign key (to_state_id) references states (id) on delete cascade
);

insert into state_transitions (from_state_id, to_state_id, description, enter_job_name, exit_job_name, configuration)
select from_state_id, to_state_id, description, null, null, '{}'::jsonb
from workflow_state_transitions;

create table metadata_job_history
(
    id        uuid    not null,
    version   int     not null,
    job_name  varchar not null,
    job_id    uuid    not null,
    status    varchar,
    created   timestamp default now(),
    complete  timestamp,
    success   boolean   default false,
    principal uuid    not null,
    primary key (id, version),
    foreign key (id) references metadata (id) on delete cascade
);

create index ix_metadata_job_history on metadata_job_history (job_id) where complete is null;

create table collection_job_history
(
    id        uuid    not null,
    job_name  varchar not null,
    job_id    uuid    not null,
    status    varchar,
    created   timestamp default now(),
    complete  timestamp,
    success   boolean   default false,
    principal uuid    not null,
    primary key (id),
    foreign key (id) references metadata (id) on delete cascade
);

create index ix_collection_job_history on collection_job_history (job_id) where complete is null;