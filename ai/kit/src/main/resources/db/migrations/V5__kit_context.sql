create type kit.context_reset as enum ('workspace', 'fresh');

alter table kit.runtime_session add column context_manifest jsonb, add column context_reset kit.context_reset;
alter table kit.runtime_turn add column context_manifest jsonb;
