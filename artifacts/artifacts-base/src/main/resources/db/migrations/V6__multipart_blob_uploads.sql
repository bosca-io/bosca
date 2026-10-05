ALTER TABLE artifacts.blobs
    ADD COLUMN storage_path TEXT;

ALTER TABLE artifacts.upload_sessions
    ADD COLUMN storage_upload_id TEXT,
    ADD COLUMN digest_state BYTEA;
