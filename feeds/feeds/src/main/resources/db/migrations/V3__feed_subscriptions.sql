-- ---------------------------------------------------------------------------
-- feed_subscriptions -- a profile's subscription to a feed source (REQ-16).
--
-- A user subscribes to feed sources; their assembled feed (REQ-19) draws items from the union of
-- their subscriptions and their own user-owned sources. Keyed by (profile_id, source_id) so a
-- subscribe is idempotent (on conflict do nothing). No cross-schema FK by design.
-- ---------------------------------------------------------------------------

create table feeds.feed_subscriptions (
    profile_id uuid        not null,   -- the subscribing profile
    source_id  uuid        not null,   -- = content Source.id (the feed source)
    created    timestamptz not null default now(),
    primary key (profile_id, source_id)
);

create index feed_subscriptions_source_idx on feeds.feed_subscriptions (source_id);
