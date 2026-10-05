create table principal_exchange_tokens
(
    token        varchar                  not null,
    principal_id uuid                     not null,
    created      timestamp with time zone not null default now(),
    expires      timestamp with time zone not null default now() + '5 minutes'::interval,
    primary key (token),
    foreign key (principal_id) references principals (id) on delete cascade
);

create index principal_exchange_tokens_expires_ix on principal_exchange_tokens (expires asc);
