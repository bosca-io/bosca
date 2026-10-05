-- Pull-request, review, comment, branch, tag, and push notifications share one
-- optional preference. It is a system key because seeded pipelines depend on
-- its stable identity, but recipients remain free to opt out per channel.
INSERT INTO communications.notification_types
    (key, name, description, optional, system, display_order)
VALUES
    ('git_activity', 'Git activity', 'Pull requests, reviews, comments, branches, tags, and pushes.', true, true, 4)
ON CONFLICT (key) DO NOTHING;
