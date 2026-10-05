create table analytics_dashboard_permissions
(
    dashboard_id uuid              not null,
    group_id     uuid              not null,
    action       permission_action not null,
    primary key (dashboard_id, group_id, action),
    foreign key (dashboard_id) references analytics_dashboards (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);
