create type public.profile_relationship_request_status as enum (
    'pending',
    'approved',
    'declined',
    'cancelled'
);

create table public.profile_relationship_requests
(
    id           uuid                                           not null default gen_random_uuid(),
    requester_profile_id uuid                                  not null,
    target_profile_id    uuid                                  not null,
    type         varchar                                        not null,
    attributes   jsonb,
    status       public.profile_relationship_request_status     not null default 'pending',
    version      bigint                                         not null default 0,
    created      timestamp with time zone                       not null default now(),
    modified     timestamp with time zone                       not null default now(),
    primary key (id),
    foreign key (requester_profile_id) references public.profiles (id) on delete cascade,
    foreign key (target_profile_id) references public.profiles (id) on delete cascade,
    constraint profile_relationship_request_distinct_profiles check (requester_profile_id <> target_profile_id)
);

create unique index profile_relationship_requests_pending_unique_idx
    on public.profile_relationship_requests (requester_profile_id, target_profile_id, type)
    where status = 'pending';

create index profile_relationship_requests_pending_target_idx
    on public.profile_relationship_requests (target_profile_id, type, requester_profile_id)
    where status = 'pending';
