alter table meilisearch_nodes drop column api_key;
alter table meilisearch_nodes add column api_key bytea;
alter table meilisearch_nodes add column api_key_nonce bytea;
