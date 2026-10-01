-- Result caching for analytics queries.
--
-- `refresh_interval_seconds` opts a query into result caching: when set, results are
-- cached per parameter combination and refreshed in the background on that interval.
-- NULL keeps the existing behavior of executing against the analytics store every time.
ALTER TABLE analytics_queries
    ADD COLUMN refresh_interval_seconds INT;

-- Tracks each cached parameter combination per query. The cached records themselves
-- live in the distributed cache; these rows tell the background refresh job which
-- combinations to re-execute, when each was last refreshed, and which have gone idle.
CREATE TABLE analytics_query_cache_entries
(
    query_id          UUID        NOT NULL REFERENCES analytics_queries (id) ON DELETE CASCADE,
    parameters_hash   VARCHAR     NOT NULL,
    parameters        JSONB       NOT NULL,
    last_refreshed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_accessed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (query_id, parameters_hash)
);
