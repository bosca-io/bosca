alter table principal_login_revocations
    add column principal_id uuid;

update principal_login_revocations r
set principal_id = l.principal_id
from principal_logins l
where l.id = r.login_id;

alter table principal_login_revocations
    alter column principal_id set not null,
    add constraint principal_login_revocations_principal_fk
        foreign key (principal_id) references principals (id) on delete cascade;

create index ix_principal_login_revocations_principal_expires
    on principal_login_revocations (principal_id, expires_at);
