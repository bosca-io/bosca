alter table metadata_job_history add column delayed_until timestamp;
alter table collection_job_history add column delayed_until timestamp;
