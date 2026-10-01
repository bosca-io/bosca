create table principal_refresh_tokens
(
    token        varchar                  not null,
    principal_id uuid                     not null,
    created      timestamp with time zone not null default now(),
    expires      timestamp with time zone not null default now() + '30 days'::interval,
    primary key (token),
    foreign key (principal_id) references principals (id) on delete cascade
);

create index principal_refresh_tokens_ix on principal_refresh_tokens (expires asc);