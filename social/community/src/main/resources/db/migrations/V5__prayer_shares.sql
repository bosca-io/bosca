create table prayer_shares (
    prayer_id uuid not null references prayers(id) on delete cascade,
    profile_id uuid not null references profiles(id) on delete cascade,
    shared_at timestamp with time zone not null default now(),
    primary key (prayer_id, profile_id)
);

create index idx_prayer_shares_profile_id on prayer_shares(profile_id, shared_at desc);
