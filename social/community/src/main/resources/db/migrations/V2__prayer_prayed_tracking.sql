alter table prayers add column prayer_action_count int not null default 0;

create table prayer_prayed_by (
    prayer_id uuid not null references prayers(id) on delete cascade,
    profile_id uuid not null references profiles(id) on delete cascade,
    prayed_at timestamp with time zone not null default now(),
    primary key (prayer_id, profile_id)
);
