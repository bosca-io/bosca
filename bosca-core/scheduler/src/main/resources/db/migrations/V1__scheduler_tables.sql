-- Scheduler tables for managing scheduled jobs

-- Execution status enum
CREATE TYPE execution_status AS ENUM ('pending', 'running', 'completed', 'failed', 'skipped');

-- Scheduled jobs table
CREATE TABLE scheduled_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    job_name VARCHAR(255) NOT NULL,
    job_parameters JSONB NOT NULL DEFAULT '{}',
    cron_expression VARCHAR(100),
    enabled BOOLEAN NOT NULL DEFAULT true,
    allow_concurrent BOOLEAN NOT NULL DEFAULT false,
    catch_up BOOLEAN NOT NULL DEFAULT false,
    max_catch_up INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by UUID NOT NULL,
    last_run_at TIMESTAMP WITH TIME ZONE,
    next_run_at TIMESTAMP WITH TIME ZONE
);

-- Indexes for scheduled_jobs
CREATE INDEX ix_scheduled_jobs_next_run ON scheduled_jobs (next_run_at)
    WHERE enabled = true;
CREATE INDEX ix_scheduled_jobs_enabled ON scheduled_jobs (enabled);
CREATE INDEX ix_scheduled_jobs_job_name ON scheduled_jobs (job_name);

-- Scheduled job executions table
CREATE TABLE scheduled_job_executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheduled_job_id UUID NOT NULL REFERENCES scheduled_jobs(id) ON DELETE CASCADE,
    job_id UUID NOT NULL,
    scheduled_for TIMESTAMP WITH TIME ZONE NOT NULL,
    triggered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    status execution_status NOT NULL DEFAULT 'pending',
    completed_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    was_catch_up BOOLEAN NOT NULL DEFAULT false
);

-- Indexes for scheduled_job_executions
CREATE INDEX ix_scheduled_job_executions_job ON scheduled_job_executions (scheduled_job_id);
CREATE INDEX ix_scheduled_job_executions_status ON scheduled_job_executions (scheduled_job_id, status)
    WHERE status IN ('pending', 'running');
CREATE INDEX ix_scheduled_job_executions_triggered_at ON scheduled_job_executions (scheduled_job_id, triggered_at DESC);
