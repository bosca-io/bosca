alter table kit.runtime_session add column requested_settings jsonb;
alter table kit.runtime_session add column resolved_settings jsonb;
alter table kit.runtime_turn add column resolved_settings jsonb;
alter table kit.runtime_session add column restore_workspace_only boolean not null default false;
