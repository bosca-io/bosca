create table community_signup_emails
(
    email varchar not null,
    group_id uuid not null,
    created timestamp with time zone not null,
    expires timestamp with time zone not null,
    primary key (email, group_id),
    foreign key (group_id) references community_groups (id) on delete cascade
);
