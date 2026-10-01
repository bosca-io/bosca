create schema if not exists feeds;

-- ---------------------------------------------------------------------------
-- feed_sources -- operational sidecar for feed sources.
--
-- The authoritative feed spec (type, endpoint, auth, cron, ownership) lives in the content
-- Source.configuration as a typed FeedConfiguration. This table carries only the indexed /
-- queryable operational projection that the content Source (id/name/description/configuration)
-- cannot index (FEEDS-SPEC-1 Q6). Keyed by the content Source id; no cross-schema FK by design.
-- ---------------------------------------------------------------------------

create table feeds.feed_sources
(
    source_id        uuid        not null primary key, -- = content Source.id
    enabled          boolean     not null default true,
    owner_profile_id uuid,                             -- null = Bosca-managed; set = user-owned
    url              varchar     not null,             -- normalized endpoint, overlap key (REQ-18)
    scheduled_job_id uuid,                             -- the per-source ScheduledJob (REQ-3)
    etag             varchar,                          -- conditional-GET validators (REQ-4)
    last_modified    timestamptz,
    created          timestamptz not null default now(),
    modified         timestamptz not null default now(),
    deleted          timestamptz                       -- null = live
);

-- Overlap rule (REQ-18): a canonical feed URL is unique across live sources, so a user-owned
-- source cannot duplicate a Bosca-managed one.
create unique index feed_sources_url_idx
    on feeds.feed_sources (url) where deleted is null;

create index feed_sources_owner_idx on feeds.feed_sources (owner_profile_id) where deleted is null;
create index feed_sources_enabled_idx on feeds.feed_sources (enabled) where deleted is null;
