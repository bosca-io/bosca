alter table agent_tools add constraint fk_agent_tools_script_id
    foreign key (script_id) references scripting.scripts(id) on delete cascade;
