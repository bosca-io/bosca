alter table collection_job_history
    drop constraint if exists collection_job_history_id_fkey,
    add foreign key (id) references collections (id) on delete cascade;
