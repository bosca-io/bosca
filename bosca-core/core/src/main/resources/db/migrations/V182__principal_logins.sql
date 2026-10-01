create table principal_logins
(
    id           bigserial primary key,
    principal_id uuid                     not null references principals (id) on delete cascade,
    method       varchar(64)              not null,
    created      timestamp with time zone not null default now(),
    constraint principal_logins_method_not_blank check (length(trim(method)) > 0)
);

create index ix_principal_logins_principal_created
    on principal_logins (principal_id, created desc, id desc);
