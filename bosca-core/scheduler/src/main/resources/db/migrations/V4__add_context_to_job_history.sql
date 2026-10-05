-- Add context column to job_history table
ALTER TABLE job_history ADD COLUMN context JSONB;
ALTER TABLE job_history ADD COLUMN definition JSONB;
