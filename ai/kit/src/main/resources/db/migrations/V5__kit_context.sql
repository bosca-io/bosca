alter table kit.runtime_session add column context_manifest jsonb, add column context_reset text;
alter table kit.runtime_turn add column context_manifest jsonb;
