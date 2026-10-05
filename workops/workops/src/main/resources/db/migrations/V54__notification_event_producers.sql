-- A due-date notification is claimed once per configured due date. If a task's due date changes,
-- the distinct timestamp makes it eligible again when the new boundary arrives.
alter table workops.task
    add column due_notified_for timestamptz;

create index task_due_notification_idx
    on workops.task(due_date)
    where deleted_at is null and resolution_at is null and due_date is not null;

-- Sprint notifications resolve against projects, which have owners but no project-level watcher
-- aggregate. Existing custom schemes remain untouched.
update workops.notification_scheme
set event_recipients = jsonb_set(
    jsonb_set(
        event_recipients,
        '{SPRINT_STARTED}',
        '[{"type":"Owner"}]'::jsonb,
        true
    ),
    '{SPRINT_CLOSED}',
    '[{"type":"Owner"}]'::jsonb,
    true
),
version = version + 1
where id = 'b0000000-0000-0000-0000-000000000001';
