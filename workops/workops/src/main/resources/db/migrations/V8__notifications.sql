-- Work Ops — Phase 7.B (specs/workops/plan.md §7.3, R12)
--
-- Notifications, watchers, per-profile preferences, saved-filter
-- digest subscriptions, and the channel outbox. The dispatcher
-- writes IN_APP rows synchronously; EMAIL / WEBHOOK / SLACK ride
-- the outbox so a delivery worker can retry independently.

-- The scheme stores the event→recipients map as a single jsonb.
-- Same shape pattern as workops.permission_scheme.
create table workops.notification_scheme (
    id              uuid        not null default gen_random_uuid() primary key,
    name            varchar     not null unique,
    description     varchar,
    -- { "TASK_ASSIGNED": [{"type": "Assignee"}, {"type": "Watchers"}],
    --   "TASK_COMMENTED":[{"type": "Watchers"}, {"type": "MentionedUsers"}],
    --   ... }
    event_recipients jsonb      not null default '{}'::jsonb,
    version         bigint      not null default 0
);

create table workops.notification_preference (
    profile_id        uuid    not null primary key,
    -- Map<NotificationEvent, Set<NotificationChannel>>; empty
    -- channel set means "no notification for this event".
    event_channels    jsonb   not null default '{}'::jsonb,
    watch_authored    boolean not null default true,
    watch_commented   boolean not null default true,
    daily_digest      boolean not null default false,
    -- HH:MM 24-hour local-time bounds; null disables DND.
    dnd_start_local   varchar,
    dnd_end_local     varchar,
    muted_task_ids    uuid[]  not null default array[]::uuid[],
    muted_project_ids uuid[]  not null default array[]::uuid[],
    version           bigint  not null default 0
);

create table workops.notification (
    id                uuid    not null default gen_random_uuid() primary key,
    profile_id        uuid    not null,
    event             varchar not null,
    task_id           uuid,
    project_id        uuid,
    actor_profile_id  uuid,
    body              varchar not null,
    link              varchar,
    read_at           timestamptz,
    created_at        timestamptz not null default now()
);

create index notification_profile_unread_idx
    on workops.notification(profile_id, created_at desc)
    where read_at is null;

create index notification_profile_all_idx
    on workops.notification(profile_id, created_at desc);

create table workops.notification_subscription (
    saved_filter_id   uuid    not null references workops.saved_filter(id) on delete cascade,
    profile_id        uuid    not null,
    cron              varchar not null,
    time_zone         varchar not null default 'UTC',
    last_run_at       timestamptz,
    primary key (saved_filter_id, profile_id)
);

create index notification_subscription_profile_idx
    on workops.notification_subscription(profile_id);

create table workops.task_watcher (
    task_id    uuid not null references workops.task(id) on delete cascade,
    profile_id uuid not null,
    added_at   timestamptz not null default now(),
    primary key (task_id, profile_id)
);

create index task_watcher_profile_idx
    on workops.task_watcher(profile_id);

create table workops.notification_outbox (
    id           uuid    not null default gen_random_uuid() primary key,
    channel      varchar not null,
    target       varchar not null,
    payload      jsonb   not null,
    attempts     integer not null default 0,
    last_error   varchar,
    sent_at      timestamptz,
    created_at   timestamptz not null default now()
);

-- The dispatcher pulls unsent rows in created order; the partial
-- index lets the polling worker scan only the queue tail.
create index notification_outbox_pending_idx
    on workops.notification_outbox(created_at)
    where sent_at is null;

-- Seed a default scheme so projects work out of the box. Mirrors
-- the permission scheme pattern: the dispatcher falls back to the
-- seeded scheme when a project's notification_scheme_id is null.
insert into workops.notification_scheme (id, name, description, event_recipients) values (
    'b0000000-0000-0000-0000-000000000001',
    'Default Notification Scheme',
    'Reporter / Assignee / Watchers / Mentioned for the standard task-life-cycle events.',
    $$ {
        "TASK_CREATED":         [ {"type": "Reporter"} ],
        "TASK_UPDATED":         [ {"type": "Watchers"}, {"type": "Assignee"}, {"type": "Reporter"} ],
        "TASK_ASSIGNED":        [ {"type": "Assignee"} ],
        "TASK_RESOLVED":        [ {"type": "Watchers"}, {"type": "Reporter"} ],
        "TASK_CLOSED":          [ {"type": "Watchers"}, {"type": "Reporter"} ],
        "TASK_REOPENED":        [ {"type": "Watchers"}, {"type": "Assignee"}, {"type": "Reporter"} ],
        "TASK_COMMENTED":       [ {"type": "Watchers"}, {"type": "MentionedUsers"} ],
        "TASK_COMMENT_EDITED":  [ {"type": "Watchers"}, {"type": "MentionedUsers"} ],
        "TASK_COMMENT_DELETED": [ {"type": "Watchers"} ],
        "TASK_TRANSITIONED":    [ {"type": "Watchers"}, {"type": "Assignee"} ],
        "TASK_LINKED":          [ {"type": "Watchers"} ],
        "TASK_DELETED":         [ {"type": "Watchers"}, {"type": "Reporter"} ],
        "WORKLOG_LOGGED":       [ {"type": "Watchers"} ],
        "WORKLOG_UPDATED":      [ {"type": "Watchers"} ],
        "TASK_DUE":             [ {"type": "Assignee"}, {"type": "Watchers"} ],
        "SLA_BREACHED":         [ {"type": "Assignee"}, {"type": "Watchers"} ],
        "SLA_AT_RISK":          [ {"type": "Assignee"}, {"type": "Watchers"} ],
        "MENTIONED":            [ {"type": "MentionedUsers"} ],
        "WATCH_ADDED":          [ ],
        "WATCH_REMOVED":        [ ],
        "SPRINT_STARTED":       [ {"type": "Watchers"} ],
        "SPRINT_CLOSED":        [ {"type": "Watchers"} ]
    } $$::jsonb
);

-- Project rows reference the scheme via default_notification_scheme_id.
-- Phase 2 created the column as nullable; Phase 7.B backfills with the
-- seeded scheme and adds the FK.
update workops.project
   set default_notification_scheme_id = 'b0000000-0000-0000-0000-000000000001'
 where default_notification_scheme_id is null;

alter table workops.project
    add constraint project_default_notification_scheme_fk
    foreign key (default_notification_scheme_id)
    references workops.notification_scheme(id) on delete restrict;
