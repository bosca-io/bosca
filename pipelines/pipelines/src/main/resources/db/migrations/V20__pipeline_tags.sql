-- Free-form categorization labels for a pipeline, so the UI can group and filter pipelines.
-- Stored as a Postgres text[]; empty by default. Consumers read/write the whole array.
alter table pipelines.pipelines
    add column tags text[] not null default '{}';

-- GIN index so "pipelines carrying tag X" filters stay index-backed as the catalog grows.
create index if not exists pipelines_pipelines_tags_idx
    on pipelines.pipelines using gin (tags);
