alter table profiles add column modified timestamp with time zone;
update profiles set modified = created;
alter table profiles alter column modified set default now();
alter table profiles alter column modified set not null;