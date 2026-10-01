create type analytics_visualization_type as enum (
    'number',
    'bar',
    'line',
    'pie',
    'doughnut',
    'bubble',
    'scatter',
    'table'
);

create table analytics_visualizations
(
    id            uuid                         not null default gen_random_uuid(),
    key           text                         not null default gen_random_uuid()::text,
    name          text                         not null,
    description   text                         not null,
    query_id      uuid                         not null,
    type          analytics_visualization_type not null,
    configuration jsonb,
    primary key (id),
    foreign key (query_id) references analytics_queries (id) on delete cascade
);

create unique index analytics_visualizations_key_uindex on analytics_visualizations (key);
