-- Reclassify repositories that used BX before removing its content type.
update git.repositories set content_type = 'general' where content_type = 'bx_project';

create type git.repository_content_type_without_bx as enum (
    'general',
    'script_project',
    'documentation',
    'analytic_query_project',
    'agent_project',
    'pipeline_project'
);

alter table git.repositories
    alter column content_type type git.repository_content_type_without_bx
    using content_type::text::git.repository_content_type_without_bx;

drop type git.repository_content_type;
alter type git.repository_content_type_without_bx rename to repository_content_type;
