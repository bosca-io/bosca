alter table analytics_dashboard_visualizations add column configuration jsonb not null default '{}'::jsonb;
