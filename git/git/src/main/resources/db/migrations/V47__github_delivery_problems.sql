ALTER TABLE git.github_users ADD COLUMN github_username varchar;
ALTER TABLE git.github_deliveries
    ADD COLUMN problem text,
    ADD COLUMN failure_reported boolean NOT NULL DEFAULT false;
