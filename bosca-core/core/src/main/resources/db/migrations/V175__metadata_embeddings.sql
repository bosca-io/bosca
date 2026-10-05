-- Semantic content embeddings for the recommender (Phase 2). Held in a SEPARATE table, deliberately:
--   * the large 768-dim vector never rides along on metadata's hot-path `select *` reads, and
--   * writing an embedding never touches the metadata row, so it dispatches no MetadataUpdated event
--     (which would retrigger indexing -> recompute the embedding -> loop).
--
-- `CREATE EXTENSION IF NOT EXISTS` short-circuits on the existence check BEFORE the privilege check, so
-- it is a harmless no-op for the non-superuser application role Flyway runs as in prod — as long as
-- pgvector is already installed. Prod guarantees that: the standard CloudNativePG image bundles pgvector
-- and enables it as superuser at bootstrap (`postInitApplicationSQL` in helm/bosca-infra; existing
-- clusters use the one-time superuser migration job). In a superuser environment (local docker-compose)
-- this line self-enables it. It only errors if pgvector is neither installed nor creatable — in which
-- case the vector(768) column below would fail regardless.
create extension if not exists vector;

create table metadata_embeddings
(
    metadata_id uuid                     not null,
    embedding   vector(768)              not null,
    modified    timestamp with time zone not null default now(),
    primary key (metadata_id),
    foreign key (metadata_id) references metadata (id) on delete cascade
);

-- Trino-readable projection. Trino's postgresql connector can't read the pgvector `vector` type, so this
-- view exposes its text form (e.g. '[0.1,0.2,...]'); the `recommender-content-embeddings` analytics query
-- reads the view and the trainer parses the text. (A real[]-cast projection is a future option.)
create view metadata_embedding as
select metadata_id as id, embedding::text as embedding
from metadata_embeddings;
