create table infrastructure.cache_entries (
    namespace text not null, key text not null, value text, expires timestamptz not null,
    primary key(namespace, key)
);
create index on infrastructure.cache_entries(expires);
create table infrastructure.counters (
    key text primary key, value bigint not null, expires timestamptz not null
);
create index on infrastructure.counters(expires);
