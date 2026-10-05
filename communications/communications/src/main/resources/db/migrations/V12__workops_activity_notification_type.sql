-- WorkOps activity is optional because per-profile WorkOps channel preferences
-- determine whether an event is eligible for email delivery.
insert into communications.notification_types
    (key, name, description, optional, system, display_order)
values
    ('workops_activity', 'WorkOps activity', 'Task, specification, requirement, comment, and workflow activity.', true, true, 5)
on conflict (key) do nothing;
