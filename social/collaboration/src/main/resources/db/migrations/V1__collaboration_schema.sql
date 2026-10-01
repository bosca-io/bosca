create schema if not exists collaboration;

insert into public.groups (id, name, description)
values (gen_random_uuid(), 'messaging', 'Users who can be reached via @mentions and access the messaging system')
on conflict (name) do nothing;
