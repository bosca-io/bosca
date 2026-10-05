-- Adds the persistent helm repo registry. The studio's catalog page
-- reads from this table; the controller's index-fetch background loop
-- writes to it. The cached `index_yaml` is stored as raw text rather
-- than parsed JSONB so a parser update never invalidates the cache.

create table if not exists kubernetes.helm_repo (
    name           text        primary key,
    url            text        not null,
    type           text        not null default 'http',
    last_index_at  timestamptz,
    index_yaml     text,
    modified_at    timestamptz not null default now(),
    created_at     timestamptz not null default now()
);

create index if not exists helm_repo_url_idx on kubernetes.helm_repo (url);
