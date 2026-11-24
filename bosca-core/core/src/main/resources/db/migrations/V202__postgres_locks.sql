create table infrastructure.locks (
    name text primary key, owner uuid not null, expires timestamptz not null
);
create index on infrastructure.locks(expires);
