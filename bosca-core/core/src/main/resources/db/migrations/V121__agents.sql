create table agent_tools
(
    id            uuid    not null default gen_random_uuid(),
    key           varchar not null unique,
    name          varchar not null,
    description   varchar not null,
    configuration jsonb,
    primary key (id)
);

create table agents
(
    id            uuid    not null default gen_random_uuid(),
    key           varchar not null unique,
    name          varchar not null,
    description   varchar not null,
    model_id      uuid    not null references models (id),
    prompt_id     uuid    not null references prompts (id),
    configuration jsonb,
    primary key (id)
);

create table agent_sub_agents
(
    agent_id     uuid not null,
    sub_agent_id uuid not null,
    ordinal      int  not null default 0,
    primary key (agent_id, sub_agent_id),
    foreign key (agent_id) references agents (id) on delete cascade,
    foreign key (sub_agent_id) references agents (id) on delete cascade
);

create table agent_agent_tools
(
    agent_id uuid not null,
    tool_id  uuid not null,
    primary key (agent_id, tool_id),
    foreign key (agent_id) references agents (id) on delete cascade,
    foreign key (tool_id) references agent_tools (id) on delete cascade
);
