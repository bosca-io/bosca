alter table prayers
    add column title text not null default '',
    add column answered_at timestamp with time zone,
    add column last_activity_at timestamp with time zone not null default now();

update prayers set last_activity_at = modified;

create index idx_prayers_last_activity_at on prayers (last_activity_at desc, id);
