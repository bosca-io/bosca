-- Add 'cancelled' to the execution_status enum for job cancellation support
ALTER TYPE execution_status ADD VALUE IF NOT EXISTS 'cancelled' AFTER 'skipped';
