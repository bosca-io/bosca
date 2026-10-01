create type chat.object_type as enum (
    'metadata', 'collection', 'localization_key',
    'calendar_event', 'feature_flag', 'experiment'
);

alter table chat.channels
    add column object_type chat.object_type,
    add column object_id uuid;

create unique index idx_channels_object
    on chat.channels (object_type, object_id)
    where object_type is not null and object_id is not null;

alter table chat.channels
    add constraint chk_object_scope check (
        (object_type is null and object_id is null) or
        (object_type is not null and object_id is not null)
    );
