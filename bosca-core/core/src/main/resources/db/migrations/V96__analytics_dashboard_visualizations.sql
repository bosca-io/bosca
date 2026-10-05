create table analytics_dashboard_visualizations (
    dashboard_id uuid not null references analytics_dashboards(id) on delete cascade,
    visualization_id uuid not null references analytics_visualizations(id) on delete cascade,
    primary key (dashboard_id, visualization_id)
);
