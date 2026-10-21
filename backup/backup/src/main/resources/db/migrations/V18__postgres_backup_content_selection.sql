alter table backup.postgres_runs
    add column include_content_references boolean not null default false;
