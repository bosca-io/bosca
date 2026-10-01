alter table analytics_queries add column key varchar not null default gen_random_uuid()::varchar;
