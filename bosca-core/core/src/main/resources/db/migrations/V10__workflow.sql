insert into workflows (id, name, description, queue)
values ('metadata.process', 'Process Metadata', 'Process Metadata', 'metadata');

insert into workflows (id, name, description, queue)
values ('collection.process', 'Process Collection', 'Process Collection', 'metadata');

insert into workflow_states (id, name, description, type, configuration, workflow_id, exit_workflow_id,
                             entry_workflow_id)
values ('pending', 'Pending', 'pending', 'pending'::workflow_state_type, '{}', null, null, null);

insert into workflow_states (id, name, description, type, configuration, workflow_id, exit_workflow_id,
                             entry_workflow_id)
values ('processing', 'Processing', 'Initial Processing after Metadata Creation', 'processing'::workflow_state_type,
        '{}', 'metadata.process', null, null);

insert into workflow_states (id, name, description, type, configuration, workflow_id, exit_workflow_id,
                             entry_workflow_id)
values ('draft', 'Draft', 'Draft', 'draft'::workflow_state_type, '{}', null, null, null);

insert into workflow_states (id, name, description, type, configuration, workflow_id, exit_workflow_id,
                             entry_workflow_id)
values ('pending-approval', 'Pending Approval', 'Pending Approval', 'approval'::workflow_state_type, '{}', null, null,
        null);

insert into workflow_states (id, name, description, type, configuration, workflow_id, exit_workflow_id,
                             entry_workflow_id)
values ('approved', 'Approved', 'Approved', 'approved'::workflow_state_type, '{}', null, null, null);

insert into workflow_states (id, name, description, type, configuration, workflow_id, exit_workflow_id,
                             entry_workflow_id)
values ('published', 'Published', 'Published', 'published'::workflow_state_type, '{}', null, null, null);

insert into workflow_states (id, name, description, type, configuration, workflow_id, exit_workflow_id,
                             entry_workflow_id)
values ('failure', 'Failure', 'Failure', 'failure'::workflow_state_type, '{}', null, null, null);


insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('pending', 'processing', 'Content is ready, begin processing');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('processing', 'draft', 'Content has been processed, now in draft mode');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('processing', 'failure', 'Processing failed');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('failure', 'processing', 'Re-processing after failure');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('draft', 'processing', 'Re-processing draft');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('draft', 'published', 'Publish a draft');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('draft', 'pending-approval', 'Move draft to pending approval');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('pending-approval', 'approved', 'Approve');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('pending-approval', 'draft', 'Move back to draft');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('approved', 'published', 'Approve');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('approved', 'draft', 'Move back to draft');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('published', 'draft', 'Unpublish');
