-- AgentResource — a reference resource tied to agents. Like AgentTool, exactly one
-- implementation variant is set (static_text, metadata_id, document_metadata_id
-- [+ document_version], content_metadata_id, script_id, or graphql_operation [+ transforms]);
-- the application layer enforces the XOR. Git-syncable via the agent repo's resources/ dir.
--
-- No FKs to content/scripting tables: the ai-schema migration does not depend on those
-- modules (same precedent as V7's omission of a git.repositories FK). The application
-- enforces referential integrity.

create table ai.agent_resources
(
    id                       uuid      not null default gen_random_uuid(),
    key                      varchar   not null unique,
    name                     varchar   not null,
    description              varchar   not null default '',
    configuration            jsonb,
    static_text              text,
    metadata_id              uuid,
    document_metadata_id     uuid,
    document_version         integer,
    content_metadata_id      uuid,
    script_id                uuid,
    graphql_operation        text,
    graphql_input_transform  text,
    graphql_output_transform text,
    git_repository_id        uuid,
    git_path                 varchar,
    last_sync_error          text,
    primary key (id),
    constraint agent_resources_git_repository_path_unique unique (git_repository_id, git_path)
);
