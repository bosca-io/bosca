-- Add EVERYONE segment type and banner placement/weight fields

alter type segmentation.segment_type add value 'everyone';

alter table segmentation.campaigns add column placement varchar;
alter table segmentation.campaigns add column weight integer not null default 0;

create index idx_campaigns_active_banners
    on segmentation.campaigns (placement, weight desc)
    where channel = 'banner' and status = 'active';
