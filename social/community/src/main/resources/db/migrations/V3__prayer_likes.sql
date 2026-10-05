alter table prayers add column like_count int not null default 0;

create table prayer_likes (
    prayer_id uuid not null references prayers(id) on delete cascade,
    profile_id uuid not null references profiles(id) on delete cascade,
    liked_at timestamp with time zone not null default now(),
    primary key (prayer_id, profile_id)
);
