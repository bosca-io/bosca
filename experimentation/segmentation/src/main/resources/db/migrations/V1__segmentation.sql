-- Segmentation: audience segments and campaigns

create type segmentation.segment_type as enum ('static', 'dynamic');
create type segmentation.segment_status as enum ('draft', 'active', 'paused', 'archived');
create type segmentation.notification_channel as enum ('banner', 'email', 'push');
create type segmentation.notification_status as enum ('draft', 'scheduled', 'sending', 'sent', 'failed', 'cancelled');

create table segmentation.segments (
    id              uuid primary key default gen_random_uuid(),
    name            text not null,
    description     text not null default '',
    type            segmentation.segment_type not null,
    status          segmentation.segment_status not null default 'draft',
    analytics_query_id uuid references public.analytics_queries(id) on delete set null,
    configuration   jsonb,
    last_evaluated  timestamptz,
    member_count    bigint not null default 0,
    created         timestamptz not null default now(),
    modified        timestamptz not null default now()
);

create table segmentation.segment_members (
    segment_id  uuid not null references segmentation.segments(id) on delete cascade,
    profile_id  uuid not null references public.profiles(id) on delete cascade,
    added_at    timestamptz not null default now(),
    primary key (segment_id, profile_id)
);

create index idx_segment_members_profile on segmentation.segment_members (profile_id);

create table segmentation.campaigns (
    id            uuid primary key default gen_random_uuid(),
    name          text not null,
    channel       segmentation.notification_channel not null,
    status        segmentation.notification_status not null default 'draft',
    content       jsonb,
    scheduled_at  timestamptz,
    sent_at       timestamptz,
    sent_count    bigint not null default 0,
    created       timestamptz not null default now(),
    modified      timestamptz not null default now()
);

create table segmentation.campaign_segments (
    campaign_id     uuid not null references segmentation.campaigns(id) on delete cascade,
    segment_id      uuid not null references segmentation.segments(id) on delete cascade,
    primary key (campaign_id, segment_id)
);

create index idx_campaign_segments_segment on segmentation.campaign_segments (segment_id);
