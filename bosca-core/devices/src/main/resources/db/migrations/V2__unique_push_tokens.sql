create type devices.push_provider_type as enum ('fcm', 'apns');

alter table devices.device_push_tokens
    add column provider devices.push_provider_type not null default 'fcm';

-- A token identifies one app installation within its issuing provider. Keep only
-- its newest registration before enforcing that invariant for future rotations.
delete from devices.device_push_tokens older
using devices.device_push_tokens newer
where older.provider = newer.provider
  and older.token = newer.token
  and (older.created, older.id) < (newer.created, newer.id);

alter table devices.device_push_tokens
    drop constraint device_push_tokens_device_token_key;

alter table devices.device_push_tokens
    add constraint device_push_tokens_provider_token_key unique (provider, token);
