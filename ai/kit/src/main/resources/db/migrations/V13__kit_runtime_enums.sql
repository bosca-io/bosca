create type kit.runtime_session_state as enum ('STOPPED','STARTING','READY','BUSY','WAITING_FOR_APPROVAL','STOPPING','FAILED');
create type kit.context_reset as enum ('WORKSPACE','FRESH');
create type kit.runtime_turn_status as enum ('QUEUED','SENT','COMPLETED','CANCELLED','INTERRUPTED','FAILED');
create type kit.delegation_status as enum ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED','TIMED_OUT','INTERRUPTED');

alter table kit.runtime_session
    drop constraint runtime_session_state_check,
    alter column state drop default,
    alter column state type kit.runtime_session_state using state::kit.runtime_session_state,
    alter column state set default 'STOPPED',
    alter column context_reset type kit.context_reset using context_reset::kit.context_reset;

drop index kit.runtime_turn_queue;
alter table kit.runtime_turn
    drop constraint runtime_turn_status_check,
    alter column status drop default,
    alter column status type kit.runtime_turn_status using status::kit.runtime_turn_status,
    alter column status set default 'QUEUED';
create index runtime_turn_queue on kit.runtime_turn(session_id, sequence) where status = 'QUEUED';

drop index kit.delegation_wait;
alter table kit.delegation
    drop constraint delegation_status_check,
    alter column status drop default,
    alter column status type kit.delegation_status using status::kit.delegation_status,
    alter column status set default 'QUEUED';
create index delegation_wait on kit.delegation(wait_turn_id) where waiting=true and status in ('QUEUED','RUNNING');
