-- ---------------------------------------------------------------------------
-- feed_items -- dedup mapping from an external feed item to its content Metadata.
--
-- Content exposes no cross-module finder by (source_id, source_identifier), so feeds keeps this
-- mapping itself. The (source_id, guid) PK is the idempotency key for ingestion (REQ-6): a known
-- GUID edits the existing Metadata; a new one creates it.
-- ---------------------------------------------------------------------------

create table feeds.feed_items (
    source_id   uuid        not null,   -- = content Source.id (the feed source)
    guid        varchar     not null,   -- the item's external GUID / identifier
    metadata_id uuid        not null,   -- the content Metadata this item became
    created     timestamptz not null default now(),
    modified    timestamptz not null default now(),
    primary key (source_id, guid)
);

create index feed_items_metadata_idx on feeds.feed_items (metadata_id);
