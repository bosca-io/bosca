create table chat_sessions
(
    id           uuid      not null default gen_random_uuid(),
    principal_id uuid      not null references public.principals (id) on delete cascade,
    agent_key    varchar   not null,
    title        varchar   not null default '',
    state        jsonb     not null default '{}',
    created      timestamp with time zone not null default now(),
    modified     timestamp with time zone not null default now(),
    primary key (id)
);
create index idx_chat_sessions_principal on chat_sessions (principal_id, modified desc);

create table chat_messages
(
    id             varchar   not null,
    session_id     uuid      not null references chat_sessions (id) on delete cascade,
    author         varchar   not null,
    content        jsonb,
    event_data     jsonb     not null,
    created        timestamp with time zone not null default now(),
    primary key (id)
);
create index idx_chat_messages_session on chat_messages (session_id, created);
