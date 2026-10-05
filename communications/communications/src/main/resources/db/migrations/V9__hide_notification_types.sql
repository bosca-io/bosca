ALTER TABLE communications.notification_types
    ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT false;
