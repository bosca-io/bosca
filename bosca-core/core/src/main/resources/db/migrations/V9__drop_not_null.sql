alter table workflow_activity_storage_systems
    alter column configuration drop not null;

alter table workflow_activity_prompts
    alter column configuration drop not null;

alter table workflow_activity_models
    alter column configuration drop not null;

alter table storage_system_models
    alter column configuration drop not null;