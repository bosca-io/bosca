-- Keep the receipt when content is forgotten or purged so a tool retry cannot resurrect it.
create table ai.memory_saves (
    principal_id uuid not null references public.principals(id) on delete cascade,
    request_id uuid not null,
    input_digest varchar not null,
    metadata_id uuid,
    primary key (principal_id, request_id)
);

create table ai.memory_reads (
    session_id uuid not null references ai.chat_sessions(id) on delete cascade,
    metadata_id uuid not null,
    revision varchar not null,
    source_changed boolean not null,
    primary key (session_id, metadata_id)
);
