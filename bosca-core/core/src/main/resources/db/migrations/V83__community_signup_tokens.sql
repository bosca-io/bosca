create table community_group_signup_tokens
(
    token    varchar                  not null,
    group_id uuid                     not null,
    created  timestamp with time zone not null default now(),
    expires  timestamp with time zone not null default now() + interval '60 day',
    primary key (token),
    foreign key (group_id) references community_groups (id) on delete cascade
);

create table community_group_signup_email
(
    email    varchar                  not null,
    group_id uuid                     not null,
    created  timestamp with time zone not null default now(),
    expires  timestamp with time zone not null default now() + interval '60 day',
    primary key (email),
    foreign key (group_id) references community_groups (id) on delete cascade
);