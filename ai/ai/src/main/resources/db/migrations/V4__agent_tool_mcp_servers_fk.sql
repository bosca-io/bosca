alter table agent_tools add constraint fk_agent_tools_mcp_server_id
    foreign key (mcp_server_id) references ai.mcp_server_registrations(id) on delete cascade;
