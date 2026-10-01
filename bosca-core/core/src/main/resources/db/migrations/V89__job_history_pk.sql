alter table metadata_job_history
    drop constraint if exists metadata_job_history_pkey,
    add primary key (id, version, job_id);

alter table collection_job_history
    drop constraint if exists collection_job_history_pkey,
    add primary key (id, job_id);
