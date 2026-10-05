create table backups
(
    id            uuid primary key              default gen_random_uuid(),
    status        varchar          not null     default 'pending',
    path          varchar,
    error         text,
    include_files boolean          not null     default true,
    created       timestamp with time zone not null default now(),
    modified      timestamp with time zone not null default now()
);
