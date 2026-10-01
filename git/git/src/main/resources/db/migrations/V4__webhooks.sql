create table git.webhooks (
    id              uuid primary key default gen_random_uuid(),
    repository_id   uuid not null references git.repositories(id) on delete cascade,
    url             varchar not null,
    secret          varchar not null,
    events          varchar[] not null default '{}',
    active          boolean not null default true,
    created         timestamptz not null default now()
);
create index idx_git_webhooks_repo on git.webhooks (repository_id) where active = true;

create table git.webhook_deliveries (
    id              uuid primary key default gen_random_uuid(),
    webhook_id      uuid not null references git.webhooks(id) on delete cascade,
    event           varchar not null,
    payload         text not null,
    response_status int,
    response_body   text,
    delivered_at    timestamptz,
    retry_count     int not null default 0,
    success         boolean not null default false,
    created         timestamptz not null default now()
);
create index idx_git_webhook_deliveries_webhook on git.webhook_deliveries (webhook_id, created desc);
