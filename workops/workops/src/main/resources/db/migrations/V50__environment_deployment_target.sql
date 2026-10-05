create type workops.deploy_target_kind as enum ('helm', 'helm_values', 'google_play', 'app_store');

alter table workops.environment_deployment
    add column target_kind workops.deploy_target_kind not null default 'helm';

drop index workops.env_deploy_active_idx;

create index env_deploy_active_idx
    on workops.environment_deployment(environment_id, project_id, target_kind)
    where status = 'DEPLOYED';
