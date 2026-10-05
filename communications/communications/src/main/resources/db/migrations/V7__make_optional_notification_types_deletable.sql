-- Digests and marketing are seeded catalog defaults, but unlike transactional and security
-- notifications they are optional product choices rather than protected system contracts.
-- Mark them as admin-managed so Studio exposes the existing edit and delete controls.

UPDATE communications.notification_types
SET system = false,
    updated_at = now()
WHERE key IN ('digest', 'marketing')
  AND optional = true
  AND system = true;
