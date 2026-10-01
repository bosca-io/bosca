-- Rename scheduled_job_executions to job_history and add source column

-- Add job_history_source enum
CREATE TYPE job_history_source AS ENUM ('scheduler', 'event');

-- Make scheduled_job_id nullable
ALTER TABLE scheduled_job_executions ALTER COLUMN scheduled_job_id DROP NOT NULL;

-- Add source column with default 'scheduler' for existing rows
ALTER TABLE scheduled_job_executions ADD COLUMN source job_history_source NOT NULL DEFAULT 'scheduler';

-- Rename table
ALTER TABLE scheduled_job_executions RENAME TO job_history;

-- Rename indexes
ALTER INDEX ix_scheduled_job_executions_job RENAME TO ix_job_history_scheduled_job;
ALTER INDEX ix_scheduled_job_executions_status RENAME TO ix_job_history_status;
ALTER INDEX ix_scheduled_job_executions_triggered_at RENAME TO ix_job_history_triggered_at;

-- Add index on source
CREATE INDEX ix_job_history_source ON job_history (source);
