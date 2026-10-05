INSERT INTO communications.notification_types
    (key, name, description, optional, system, display_order)
VALUES
    ('social_activity', 'Social activity', 'Chat messages, relationship updates, and channel invitations.', true, true, 6)
ON CONFLICT (key) DO NOTHING;
