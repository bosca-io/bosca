-- Recommendations: content recommendation strategies, pre-computed recommendations, and user dismissals

create type recommendations.strategy_type as enum ('content_based', 'segment_based', 'trending', 'curated', 'collaborative');
create type recommendations.strategy_status as enum ('draft', 'active', 'paused', 'archived');

create table recommendations.strategies (
    id                   uuid primary key default gen_random_uuid(),
    name                 text not null,
    description          text not null default '',
    type                 recommendations.strategy_type not null,
    status               recommendations.strategy_status not null default 'draft',
    analytics_query_id   uuid references public.analytics_queries(id) on delete set null,
    configuration        jsonb,
    priority             int not null default 0,
    max_recommendations  int not null default 10,
    scheduled_job_id     uuid,
    last_evaluated       timestamptz,
    created              timestamptz not null default now(),
    modified             timestamptz not null default now()
);

create table recommendations.strategy_segments (
    strategy_id  uuid not null references recommendations.strategies(id) on delete cascade,
    segment_id   uuid not null references segmentation.segments(id) on delete cascade,
    primary key (strategy_id, segment_id)
);

create table recommendations.recommendations (
    id              uuid primary key default gen_random_uuid(),
    profile_id      uuid not null references public.profiles(id) on delete cascade,
    metadata_id     uuid,
    collection_id   uuid,
    strategy_id     uuid not null references recommendations.strategies(id) on delete cascade,
    score           double precision not null default 0.0,
    reason          text,
    context         jsonb,
    expires_at      timestamptz,
    created         timestamptz not null default now(),
    check (metadata_id is not null or collection_id is not null)
);

create index idx_recommendations_profile on recommendations.recommendations (profile_id, score desc);
create index idx_recommendations_strategy on recommendations.recommendations (strategy_id);
create index idx_recommendations_expires on recommendations.recommendations (expires_at) where expires_at is not null;
create unique index idx_recommendations_unique_metadata on recommendations.recommendations (profile_id, metadata_id, strategy_id) where metadata_id is not null;
create unique index idx_recommendations_unique_collection on recommendations.recommendations (profile_id, collection_id, strategy_id) where collection_id is not null;

create table recommendations.dismissals (
    id              uuid primary key default gen_random_uuid(),
    profile_id      uuid not null references public.profiles(id) on delete cascade,
    metadata_id     uuid,
    collection_id   uuid,
    created         timestamptz not null default now(),
    check (metadata_id is not null or collection_id is not null)
);

create unique index idx_dismissals_metadata on recommendations.dismissals (profile_id, metadata_id) where metadata_id is not null;
create unique index idx_dismissals_collection on recommendations.dismissals (profile_id, collection_id) where collection_id is not null;

create table recommendations.placements (
    id             uuid primary key default gen_random_uuid(),
    name           text not null,
    description    text not null default '',
    slug           text not null unique,
    max_items      int not null default 5,
    configuration  jsonb,
    created        timestamptz not null default now(),
    modified       timestamptz not null default now()
);

create table recommendations.placement_strategies (
    placement_id  uuid not null references recommendations.placements(id) on delete cascade,
    strategy_id   uuid not null references recommendations.strategies(id) on delete cascade,
    priority      int not null default 0,
    primary key (placement_id, strategy_id)
);
