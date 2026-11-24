create schema if not exists sharedqueue;
create table sharedqueue.jobs (
    queue text not null, id uuid not null, payload text not null,
    available timestamptz, claimed_until timestamptz,
    primary key (queue, id)
);
create index on sharedqueue.jobs(queue, available) where available is not null;
