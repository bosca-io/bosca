ALTER TYPE workflow_state_type ADD VALUE 'advertised' AFTER 'approved';

commit;

insert into workflows (id, name, description, queue, configuration)
values ('content.advertised', 'Content Advertised', 'Content Advertised', 'default', '{}'::jsonb);

insert into workflow_states (id, name, description, type, configuration, workflow_id)
values ('advertised', 'Advertised', 'Advertised', 'advertised', '{}'::jsonb, 'content.advertised');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('draft', 'advertised', 'Draft to Advertised');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('advertised', 'draft', 'Advertised to Draft');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('advertised', 'published', 'Advertised to Published');

insert into workflow_state_transitions (from_state_id, to_state_id, description)
values ('published', 'advertised', 'Published to Advertised');