-- Work Ops — Phase 14 (specs/workops/plan.md §14, R28)
--
-- Service-desk portal core. Phase 14 ships Portal + RequestType +
-- PortalUser + PortalToken plus a comment_exposure column on the
-- existing task_comment table so portal viewers see only
-- PORTAL_VISIBLE comments. The full PortalForm builder lands
-- with Phase 20.

create table workops.portal (
    id                              uuid    not null default gen_random_uuid() primary key,
    slug                            varchar not null unique,
    name                            varchar not null,
    description                     varchar,
    project_id                      uuid    not null references workops.project(id) on delete cascade,
    theme_color_hex                 varchar not null default '#3b82f6',
    logo_storage_object_id          uuid,
    welcome_markdown                varchar,
    support_email                   varchar not null,
    auth_mode                       varchar not null default 'AUTHENTICATED_ONLY',
    anonymous_allowlist_domains     varchar[] not null default array[]::varchar[],
    sla_policy_id                   uuid    references workops.sla_policy(id) on delete set null,
    enabled                         boolean not null default true,
    version                         bigint  not null default 0
);

create index portal_project_idx on workops.portal(project_id);
create index portal_enabled_idx on workops.portal(enabled) where enabled = true;

create table workops.portal_request_type (
    id                  uuid    not null default gen_random_uuid() primary key,
    portal_id           uuid    not null references workops.portal(id) on delete cascade,
    name                varchar not null,
    description         varchar,
    task_type_id        uuid    not null references workops.task_type(id) on delete restrict,
    default_priority_id uuid    references workops.priority(id) on delete set null,
    display_order       integer not null default 0,
    unique (portal_id, name)
);

create index portal_request_type_portal_idx on workops.portal_request_type(portal_id);

create table workops.portal_user (
    id              uuid    not null default gen_random_uuid() primary key,
    portal_id       uuid    not null references workops.portal(id) on delete cascade,
    email           varchar not null,
    profile_id      uuid,
    display_name    varchar,
    created_at      timestamptz not null default now(),
    unique (portal_id, email)
);

create index portal_user_email_idx on workops.portal_user(portal_id, email);

create table workops.portal_token (
    id                  uuid    not null default gen_random_uuid() primary key,
    portal_user_id      uuid    not null references workops.portal_user(id) on delete cascade,
    task_id             uuid    references workops.task(id) on delete cascade,
    token_hash          varchar not null,
    expires_at          timestamptz not null,
    created_at          timestamptz not null default now(),
    revoked_at          timestamptz
);

-- The polling read path filters by token_hash + expiry.
create index portal_token_active_hash_idx
    on workops.portal_token(token_hash)
    where revoked_at is null;
create index portal_token_user_idx on workops.portal_token(portal_user_id);

-- Extend task_comment with a portal-visibility flag. Existing
-- rows default to INTERNAL; Phase 14 portal-submitted comments
-- write PORTAL_VISIBLE.
alter table workops.task_comment
    add column if not exists comment_exposure varchar not null default 'INTERNAL';
