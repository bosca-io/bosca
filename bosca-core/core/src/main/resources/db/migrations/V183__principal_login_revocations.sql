alter table principals
    add column has_login_revocations boolean not null default false;

alter table principal_logins
    add column revoked_at timestamp with time zone;

create index ix_principal_logins_active
    on principal_logins (principal_id, revoked_at)
    where revoked_at is null;

alter table principal_refresh_tokens
    add column login_id bigint;

create index ix_principal_refresh_tokens_login_id
    on principal_refresh_tokens (login_id)
    where login_id is not null;

alter table principal_refresh_tokens
    add constraint principal_refresh_tokens_login_fk
        foreign key (login_id) references principal_logins (id) on delete cascade;

create table principal_login_revocations
(
    login_id   bigint                   primary key references principal_logins (id) on delete cascade,
    expires_at timestamp with time zone not null,
    created    timestamp with time zone not null default now()
);

create index ix_principal_login_revocations_expires
    on principal_login_revocations (expires_at);
