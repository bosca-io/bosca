-- Extend AgentTool with additional implementation variants (pinned GraphQL operation +
-- declarative transforms, prompt+model, agent-as-tool).
--
-- The prompt/model/agent FKs use `on delete cascade`, matching V4's mcp_server_id FK:
-- a prompt-/model-/agent-backed tool is meaningless once its backing entity is gone.

alter table agent_tools
    add column graphql_operation        text,
    add column graphql_input_transform  text,
    add column graphql_output_transform text,
    add column prompt_id                uuid,
    add column model_id                 uuid,
    add column agent_id                 uuid,
    add constraint fk_agent_tools_prompt_id foreign key (prompt_id) references prompts (id) on delete cascade,
    add constraint fk_agent_tools_model_id foreign key (model_id) references models (id) on delete cascade,
    add constraint fk_agent_tools_agent_id foreign key (agent_id) references agents (id) on delete cascade;
