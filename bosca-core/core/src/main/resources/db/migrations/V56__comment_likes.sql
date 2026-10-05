alter table metadata_comments
    add column likes int not null default 0;

create table metadata_comment_likes
(
    comment_id bigint null,
    profile_id uuid   not null,
    created    timestamp with time zone default now(),
    primary key (comment_id, profile_id),
    foreign key (comment_id) references metadata_comments (id) on delete cascade,
    foreign key (profile_id) references profiles (id) on delete cascade
);
