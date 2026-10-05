create table localization.string_metadata
(
    string_id   uuid not null references localization.strings (id) on delete cascade,
    metadata_id uuid not null,
    created     timestamp with time zone not null default now(),
    primary key (string_id, metadata_id)
);
