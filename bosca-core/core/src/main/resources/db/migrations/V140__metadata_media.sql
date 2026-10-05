create table metadata_media
(
    metadata_id          uuid                     not null primary key,
    status               varchar                  not null,
    hls_url              varchar,
    hls_audio_only_url   varchar,
    download_url         varchar,
    thumbnail_url        varchar,
    animated_preview_url varchar,
    duration_seconds     double precision,
    max_resolution       varchar,
    aspect_ratio         varchar,
    transcriptions       jsonb                    not null default '[]'::jsonb,
    provider_attributes  jsonb                    not null default '{}'::jsonb,
    created              timestamp with time zone not null default now(),
    modified             timestamp with time zone not null default now(),
    foreign key (metadata_id) references metadata (id) on delete cascade
);
