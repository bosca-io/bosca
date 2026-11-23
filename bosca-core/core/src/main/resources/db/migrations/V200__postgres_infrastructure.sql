create schema if not exists infrastructure;
create table infrastructure.pubsub_messages (
    id uuid primary key, channel text not null, payload text not null,
    expires timestamptz not null default (clock_timestamp() + interval '5 minutes')
);
create index on infrastructure.pubsub_messages(expires);
