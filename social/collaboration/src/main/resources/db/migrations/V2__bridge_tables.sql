create type collaboration.bridge_platform as enum ('slack', 'teams');

create table collaboration.bridge_bindings (
    id                  uuid                              not null default gen_random_uuid(),
    channel_id          uuid                              not null references chat.channels(id) on delete cascade,
    platform            collaboration.bridge_platform     not null,
    external_channel_id varchar                           not null,
    workspace_id        varchar                           not null,
    bot_token_nonce     bytea,
    bot_token_data      bytea,
    webhook_url         varchar,
    active              boolean                           not null default true,
    created_at          timestamptz                       not null default now(),
    primary key (id),
    unique (channel_id, platform, external_channel_id)
);

create table collaboration.bridge_identity_map (
    id               uuid                              not null default gen_random_uuid(),
    platform         collaboration.bridge_platform     not null,
    external_user_id varchar                           not null,
    workspace_id     varchar                           not null,
    profile_id       uuid                              references public.profiles(id) on delete set null,
    display_name     varchar                           not null,
    email            varchar,
    primary key (id),
    unique (platform, external_user_id, workspace_id)
);

create table collaboration.bridge_message_map (
    id                  uuid                              not null default gen_random_uuid(),
    channel_id          uuid                              not null references chat.channels(id) on delete cascade,
    sequence            bigint                            not null,
    platform            collaboration.bridge_platform     not null,
    external_message_id varchar                           not null,
    primary key (id),
    unique (channel_id, sequence, platform),
    unique (platform, external_message_id)
);
