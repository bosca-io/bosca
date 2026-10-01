alter table analytics_dashboard_visualizations drop constraint analytics_dashboard_visualizations_pkey;
alter table analytics_dashboard_visualizations add column id uuid not null default gen_random_uuid();
alter table analytics_dashboard_visualizations add primary key (id);
