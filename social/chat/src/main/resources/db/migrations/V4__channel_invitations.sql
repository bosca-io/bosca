create type chat.channel_invitation_status as enum (
    'pending',
    'accepted',
    'declined',
    'cancelled'
);

create table chat.channel_invitations
(
    id                  uuid                           not null default gen_random_uuid(),
    channel_id          uuid                           not null references chat.channels (id) on delete cascade,
    inviter_profile_id  uuid                           not null references public.profiles (id) on delete cascade,
    invitee_profile_id  uuid                           not null references public.profiles (id) on delete cascade,
    role                text                           not null,
    status              chat.channel_invitation_status not null default 'pending',
    version             bigint                         not null default 0,
    created             timestamp with time zone       not null default now(),
    modified            timestamp with time zone       not null default now(),
    primary key (id),
    constraint channel_invitation_profiles_differ check (inviter_profile_id <> invitee_profile_id)
);

create unique index channel_invitations_pending_unique_idx
    on chat.channel_invitations (channel_id, invitee_profile_id)
    where status = 'pending';

create index channel_invitations_pending_invitee_idx
    on chat.channel_invitations (invitee_profile_id, created desc)
    where status = 'pending';

create index channel_invitations_pending_inviter_idx
    on chat.channel_invitations (inviter_profile_id, created desc)
    where status = 'pending';
