-- Create analytic_queries table
CREATE TABLE analytic_queries (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    query_definition JSONB NOT NULL,
    parameters JSONB NOT NULL,
    data_source VARCHAR(255) NOT NULL,
    refresh_interval INTEGER CHECK (refresh_interval > 0),
    created TIMESTAMP NOT NULL DEFAULT NOW(),
    updated TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by UUID NOT NULL,
    PRIMARY KEY (id)
);

-- Add constraints
ALTER TABLE analytic_queries ADD CONSTRAINT uk_analytic_queries_name UNIQUE (name);
ALTER TABLE analytic_queries ADD CONSTRAINT ck_analytic_queries_name_length CHECK (length(name) <= 255);
ALTER TABLE analytic_queries ADD CONSTRAINT ck_analytic_queries_description_not_empty CHECK (length(trim(description)) > 0);
ALTER TABLE analytic_queries ADD CONSTRAINT ck_analytic_queries_data_source_not_empty CHECK (length(trim(data_source)) > 0);

-- Add indexes for performance
CREATE INDEX idx_analytic_queries_name ON analytic_queries(name);
CREATE INDEX idx_analytic_queries_data_source ON analytic_queries(data_source);
CREATE INDEX idx_analytic_queries_created_by ON analytic_queries(created_by);
CREATE INDEX idx_analytic_queries_created ON analytic_queries(created);

-- Add comments
COMMENT ON TABLE analytic_queries IS 'Stores analytic query definitions and configurations';
COMMENT ON COLUMN analytic_queries.id IS 'Unique identifier for the analytic query';
COMMENT ON COLUMN analytic_queries.name IS 'Human-readable name for the query (unique)';
COMMENT ON COLUMN analytic_queries.description IS 'Detailed description of the query purpose and functionality';
COMMENT ON COLUMN analytic_queries.query_definition IS 'JSON structure defining the query type, source, and configuration';
COMMENT ON COLUMN analytic_queries.parameters IS 'JSON array of parameter definitions for the query';
COMMENT ON COLUMN analytic_queries.data_source IS 'Identifier for the data source (e.g., postgres, bigquery, api)';
COMMENT ON COLUMN analytic_queries.refresh_interval IS 'Automatic refresh interval in seconds (null for manual refresh)';
COMMENT ON COLUMN analytic_queries.created IS 'Timestamp when the query was created';
COMMENT ON COLUMN analytic_queries.updated IS 'Timestamp when the query was last modified';
COMMENT ON COLUMN analytic_queries.created_by IS 'UUID of the user who created the query';
