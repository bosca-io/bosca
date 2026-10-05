alter table models add column key varchar;
alter table prompts add column key varchar;

update models set key = name;
update prompts set key = name;

alter table models alter column key set not null;
alter table prompts alter column key set not null;