alter table backup.postgres_runs add column content_source jsonb;

alter table backup.postgres_runs add constraint postgres_runs_content_source_object
    check (content_source is null or jsonb_typeof(content_source) = 'object');

alter table backup.postgres_runs add constraint postgres_runs_content_source_references
    check (content_source is null or include_content_references);
