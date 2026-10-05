-- Extend the seeded notification scheme to the spec and requirement events
-- already emitted by WorkOps. Existing custom schemes remain untouched.
update workops.notification_scheme
set event_recipients = event_recipients || $$ {
    "SPEC_CREATED":          [ {"type": "Owner"} ],
    "SPEC_UPDATED":          [ {"type": "Owner"}, {"type": "Watchers"} ],
    "SPEC_DELETED":          [ {"type": "Owner"}, {"type": "Watchers"} ],
    "SPEC_TRANSITIONED":     [ {"type": "Owner"}, {"type": "Watchers"} ],
    "SPEC_COMMENTED":        [ {"type": "Owner"}, {"type": "Watchers"} ],
    "REQUIREMENT_COMMENTED": [ {"type": "Assignee"}, {"type": "Watchers"} ]
} $$::jsonb,
    version = version + 1
where id = 'b0000000-0000-0000-0000-000000000001';
