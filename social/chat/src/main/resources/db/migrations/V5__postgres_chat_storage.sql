create table chat.message_sequence (singleton boolean primary key default true check (singleton), value bigint not null);
insert into chat.message_sequence values (true, 0);
create table chat.messages (
    sequence bigint primary key, channel_id uuid not null references chat.channels(id) on delete cascade,
    sender_id uuid not null, client_id uuid not null, created timestamptz not null, payload jsonb not null
);
create index on chat.messages(channel_id, sequence);
create table chat.message_deduplication (
    channel_id uuid not null references chat.channels(id) on delete cascade, sender_id uuid not null, client_id uuid not null,
    sequence bigint not null, expires timestamptz not null, primary key(channel_id, sender_id, client_id)
);
create index on chat.message_deduplication(expires);
create table chat.reactions (
    channel_id uuid not null references chat.channels(id) on delete cascade, sequence bigint not null references chat.messages(sequence) on delete cascade,
    profile_id uuid not null, id uuid not null unique, emoji text not null, primary key(channel_id, sequence, profile_id, emoji)
);
create index on chat.reactions(profile_id);
create table chat.read_positions (
    channel_id uuid not null references chat.channels(id) on delete cascade, profile_id uuid not null, sequence bigint not null,
    primary key(channel_id, profile_id)
);
create index on chat.read_positions(profile_id);
create table chat.typing (
    channel_id uuid not null references chat.channels(id) on delete cascade, profile_id uuid not null, expires timestamptz not null,
    primary key(channel_id, profile_id)
);
create index on chat.typing(expires);
create index on chat.typing(profile_id);
create table chat.presence (profile_id uuid primary key, status text not null, expires timestamptz not null);
create index on chat.presence(expires);
create table chat.message_events (
    id bigint generated always as identity primary key,
    channel_id uuid not null references chat.channels(id) on delete cascade,
    payload jsonb not null
);
