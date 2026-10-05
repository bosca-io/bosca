insert into workflows (id, name, description, queue, configuration)
values ('profile.update.storage', 'Profile Update Storage', '', 'metadata', '{}'::jsonb);

insert into workflows (id, name, description, queue, configuration)
values ('metadata.update.storage', 'Metadata Update Storage', '', 'metadata', '{}'::jsonb);
insert into workflows (id, name, description, queue, configuration)
values ('metadata.delete.finalize', 'Metadata Delete Finalize', '', 'metadata', '{}'::jsonb);
insert into workflows (id, name, description, queue, configuration)
values ('metadata.delayed.transition', 'Metadata Delayed Transition', '', 'metadata', '{}'::jsonb);

insert into workflows (id, name, description, queue, configuration)
values ('collection.update.storage', 'Collection Update Storage', '', 'metadata', '{}'::jsonb);
insert into workflows (id, name, description, queue, configuration)
values ('collection.delete.finalize', 'Collection Delete Finalize', '', 'metadata', '{}'::jsonb);
insert into workflows (id, name, description, queue, configuration)
values ('collection.delayed.transition', 'Collection Delayed Transition', '', 'metadata', '{}'::jsonb);

insert into workflows (id, name, description, queue, configuration)
values ('storage.index.initialize', 'Storage Index Initialize', '', 'metadata', '{}'::jsonb);
