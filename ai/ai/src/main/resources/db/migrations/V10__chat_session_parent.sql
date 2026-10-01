-- A chat session may hang off of a parent chat session (e.g. a sub-thread). Self-referential and
-- nullable; if a parent is deleted its children are detached rather than removed.
alter table ai.chat_sessions
    add column parent_session_id uuid references ai.chat_sessions (id) on delete cascade;

create index chat_sessions_parent_session_id on ai.chat_sessions (parent_session_id);
