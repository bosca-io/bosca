-- Preserve every tokenizer-bounded embedding chunk and the source-token coverage needed for
-- overlap-neutral aggregation. Semantic embeddings are derived data; the refresh backfill repopulates
-- this table after the schema change, so legacy whole-document vectors are deliberately discarded.
drop view metadata_embedding;

truncate table metadata_embeddings;

alter table metadata_embeddings
    drop constraint metadata_embeddings_pkey;

alter table metadata_embeddings
    add column chunk_index integer not null,
    add column token_start integer not null,
    add column token_end integer not null,
    add column token_count integer not null,
    add column aggregation_weight double precision not null,
    add constraint metadata_embeddings_chunk_index_check check (chunk_index >= 0),
    add constraint metadata_embeddings_token_span_check check (
        token_start >= 0 and token_end > token_start and token_count = token_end - token_start
    ),
    add constraint metadata_embeddings_token_count_check check (token_count > 0),
    add constraint metadata_embeddings_aggregation_weight_check check (aggregation_weight > 0),
    add primary key (metadata_id, chunk_index);

create view metadata_embedding as
select
    metadata_id as id,
    chunk_index,
    token_start,
    token_end,
    token_count,
    aggregation_weight,
    embedding::text as embedding
from metadata_embeddings;
