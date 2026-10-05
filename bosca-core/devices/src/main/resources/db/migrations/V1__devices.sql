-- Devices: device registration and push tokens

create type devices.platform_type as enum ('ios', 'android', 'web', 'desktop');

create table devices.devices (
    id              uuid not null default gen_random_uuid(),
    principal_id    uuid not null,
    platform        devices.platform_type not null,
    created         timestamptz not null default now(),
    modified        timestamptz not null default now(),
    last_check_in   timestamptz not null default now(),
    installation_id varchar,
    primary key (id),
    foreign key (principal_id) references public.principals(id) on delete cascade,
    constraint devices_installation_id_key unique (installation_id)
);

create index devices_principal_id_idx on devices.devices (principal_id);

create table devices.device_push_tokens (
    id        uuid not null default gen_random_uuid(),
    device_id uuid not null,
    token     varchar not null,
    created   timestamptz not null default now(),
    primary key (id),
    foreign key (device_id) references devices.devices(id) on delete cascade,
    constraint device_push_tokens_device_token_key unique (device_id, token)
);

create index device_push_tokens_device_id_idx on devices.device_push_tokens (device_id);
