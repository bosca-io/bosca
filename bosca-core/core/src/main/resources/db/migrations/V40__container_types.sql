create type document_template_container_type as enum ('standard', 'bible');

alter table document_template_containers add column type document_template_container_type not null default 'standard'::document_template_container_type;