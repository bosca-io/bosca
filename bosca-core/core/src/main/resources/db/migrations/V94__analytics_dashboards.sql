create table analytics_dashboards
(
    id            uuid not null default gen_random_uuid(),
    key           text not null default gen_random_uuid()::text,
    name          text not null,
    description   text not null,
    configuration jsonb,
    primary key (id)
);
create unique index analytics_dashboards_key_uindex on analytics_dashboards (key);
