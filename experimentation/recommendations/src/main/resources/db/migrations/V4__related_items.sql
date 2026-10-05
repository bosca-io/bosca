-- Item-to-item ("you liked X, here's related Z") behavioral recommendations.
-- The new strategy type generates a source->related mapping from a co-occurrence analytics query
-- instead of a per-profile feed. (PG 12+ permits ADD VALUE inside a transaction as long as the new
-- value is not used in the same transaction; the table below does not reference it.)
-- Numbered V4 because this dev schema already has a (since-removed) v3 "recommendable items".
alter type recommendations.strategy_type add value 'related_content';

create table recommendations.related_items (
    source_metadata_id   uuid not null,
    related_metadata_id  uuid not null,
    strategy_id          uuid not null references recommendations.strategies(id) on delete cascade,
    score                double precision not null default 0,
    reason               text,
    created              timestamptz not null default now(),
    primary key (source_metadata_id, related_metadata_id, strategy_id)
);

-- Serve path: top related items for a given source, highest score first.
create index idx_related_items_source on recommendations.related_items (source_metadata_id, score desc);
-- Regeneration: delete-by-strategy before re-populating.
create index idx_related_items_strategy on recommendations.related_items (strategy_id);
