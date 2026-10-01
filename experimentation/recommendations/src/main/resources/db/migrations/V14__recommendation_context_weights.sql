ALTER TABLE recommendations.contexts
    ADD COLUMN weights jsonb NOT NULL DEFAULT '{}',
    ADD COLUMN revision bigint NOT NULL DEFAULT 1;
