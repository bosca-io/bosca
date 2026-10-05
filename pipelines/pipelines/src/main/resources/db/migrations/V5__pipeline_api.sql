alter table pipelines.pipelines add column key text not null default '';
alter table pipelines.pipelines add column api boolean not null default false;
alter table pipelines.pipelines add column public boolean not null default false;

-- One live pipeline per endpoint key; blank = not endpoint-exposed.
create unique index idx_pipelines_key on pipelines.pipelines (key) where deleted_at is null and key <> '';

create table pipelines.pipeline_permissions
(
    pipeline_id uuid              not null references pipelines.pipelines (id) on delete cascade,
    group_id    uuid              not null references groups (id) on delete cascade,
    action      permission_action not null,
    primary key (pipeline_id, group_id, action)
);

create index idx_pipeline_permissions_pipeline on pipelines.pipeline_permissions (pipeline_id);
