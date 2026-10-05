alter table trait_workflows
    drop constraint trait_workflows_trait_id_fkey;

alter table trait_workflows
    drop constraint trait_workflows_workflow_id_fkey;

alter table trait_workflows
    add foreign key (trait_id) references traits (id) on delete cascade;

alter table trait_workflows
    add foreign key (workflow_id) references workflows (id) on delete cascade;