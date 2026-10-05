-- Stable source identifiers make at-least-once notification jobs idempotent.
alter table workops.notification
    add column source_id uuid;

create unique index notification_source_profile_event_idx
    on workops.notification(source_id, event, profile_id)
    where source_id is not null;

-- External channels are staged durably, can be delayed until DND ends, and can be grouped into a
-- daily digest. Existing rows use their own ids as source ids so the new uniqueness invariant holds.
create type workops.notification_channel as enum ('email', 'webhook', 'slack');
create type workops.notification_outbox_action as enum ('automation_comment');

alter table workops.notification_outbox
    add column source_id uuid,
    add column event varchar not null default 'OUTBOX',
    add column action workops.notification_outbox_action,
    add column available_at timestamptz,
    add column digest boolean not null default false;

-- The legacy EMAIL payload was an opaque body and cannot satisfy the typed pipeline handoff.
-- It is intentionally discarded during this upgrade instead of fabricating missing event data.
delete from workops.notification_outbox
where lower(channel) = 'email';

update workops.notification_outbox
set action = 'automation_comment'
where lower(channel) = 'automation_comment';

alter table workops.notification_outbox
    alter column channel drop not null,
    alter column channel type workops.notification_channel using (
        case
            when lower(channel) in ('email', 'webhook', 'slack')
                then lower(channel)::workops.notification_channel
            else null
        end
    );

update workops.notification_outbox
set source_id = id
where source_id is null;

alter table workops.notification_outbox
    alter column source_id set not null,
    add constraint notification_outbox_kind_check check (num_nonnulls(channel, action) = 1);

create unique index notification_outbox_source_event_channel_target_idx
    on workops.notification_outbox(source_id, event, channel, target);

create index notification_outbox_available_idx
    on workops.notification_outbox(available_at, created_at)
    where sent_at is null;
