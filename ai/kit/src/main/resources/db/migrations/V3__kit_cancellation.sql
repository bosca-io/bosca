alter table kit.runtime_session add column cancel_requested_at timestamptz;
alter table kit.runtime_turn drop constraint runtime_turn_status_check;
alter table kit.runtime_turn add constraint runtime_turn_status_check
    check (status in ('QUEUED','SENT','COMPLETED','CANCELLED','INTERRUPTED','FAILED'));
