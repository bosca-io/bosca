-- Make analytics query cache writes definition-aware and object reads atomic.
ALTER TABLE analytics_queries
    ADD COLUMN cache_generation BIGINT NOT NULL DEFAULT 0;

ALTER TABLE analytics_query_cache_entries
    ADD COLUMN query_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN object_version UUID,
    ADD COLUMN superseded_object_version UUID,
    ADD COLUMN superseded_legacy_object BOOLEAN NOT NULL DEFAULT FALSE;

-- Keep the existing (query_id, parameters_hash) primary key so the previous
-- application version can continue writing during a rolling deployment.

CREATE INDEX analytics_query_cache_entries_due_idx
    ON analytics_query_cache_entries (last_refreshed_at, query_id)
    WHERE object_version IS NOT NULL;

CREATE INDEX analytics_query_cache_entries_idle_idx
    ON analytics_query_cache_entries (last_accessed_at);

CREATE INDEX analytics_query_cache_entries_legacy_idx
    ON analytics_query_cache_entries (created_at)
    WHERE object_version IS NULL;

-- Preserve the supported execution floor without coupling it to the
-- user-configurable background sweep cadence.
UPDATE analytics_queries
SET refresh_interval_seconds = 60
WHERE refresh_interval_seconds IS NOT NULL
  AND refresh_interval_seconds < 60;

ALTER TABLE analytics_queries
    ADD CONSTRAINT analytics_queries_refresh_interval_check
        CHECK (refresh_interval_seconds IS NULL OR refresh_interval_seconds >= 60);
