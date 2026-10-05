alter table prayers add column suppress_anniversaries boolean not null default false;

create table prayer_anniversaries (
    prayer_id uuid not null references prayers(id) on delete cascade,
    milestone text not null,
    posted_at timestamp with time zone not null default now(),
    primary key (prayer_id, milestone)
);
