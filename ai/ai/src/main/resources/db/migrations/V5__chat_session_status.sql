create type ai.chat_session_status as enum ('streaming', 'completed', 'failed');
alter table ai.chat_sessions add column status ai.chat_session_status not null default 'completed';
