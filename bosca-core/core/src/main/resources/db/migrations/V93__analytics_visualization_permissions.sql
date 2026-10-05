create table analytics_visualization_permissions
(
    visualization_id uuid              not null,
    group_id         uuid              not null,
    action           permission_action not null,
    primary key (visualization_id, group_id, action),
    foreign key (visualization_id) references analytics_visualizations (id) on delete cascade,
    foreign key (group_id) references groups (id) on delete cascade
);
