alter table metadata add column workflow_state_valid timestamp with time zone;
alter table collections add column workflow_state_valid timestamp with time zone;
alter table workflow_plans add column queue varchar;
alter table workflow_plans add column workflow_id varchar;
alter table workflow_plans add column collection_id uuid;
alter table workflow_plans add column metadata_id uuid;
alter table workflow_plans add column metadata_version int;
alter table workflow_plans add column finished timestamp with time zone default now();