create type collaboration.federation_sync_direction as enum ('bidirectional', 'inbound', 'outbound');

create table collaboration.federation_peers (
    id                  uuid        not null default gen_random_uuid(),
    name                varchar     not null,
    nats_url            varchar     not null,
    api_url             varchar     not null,
    shared_secret_nonce bytea,
    shared_secret_data  bytea,
    active              boolean     not null default true,
    created_at          timestamptz not null default now(),
    primary key (id)
);

create table collaboration.federated_channels (
    local_channel_id    uuid not null references chat.channels(id) on delete cascade,
    peer_id             uuid not null references collaboration.federation_peers(id) on delete cascade,
    remote_channel_id   uuid not null,
    sync_direction      collaboration.federation_sync_direction not null default 'bidirectional',
    primary key (local_channel_id, peer_id)
);

create table collaboration.federation_profiles (
    peer_id             uuid    not null references collaboration.federation_peers(id) on delete cascade,
    remote_profile_id   uuid    not null,
    local_profile_id    uuid    references public.profiles(id) on delete set null,
    display_name        varchar not null,
    avatar_url          varchar,
    primary key (peer_id, remote_profile_id)
);
