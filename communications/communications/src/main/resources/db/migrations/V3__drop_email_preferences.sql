-- Retire the legacy email-only preference table. V2 migrated every
-- row (preferences, tokens, and custom categories) into the
-- channel-aware model, and the last readers (the legacy GraphQL
-- surface and service) have been removed.

DROP TABLE communications.email_preferences;
