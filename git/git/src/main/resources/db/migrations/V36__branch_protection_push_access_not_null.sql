update git.branch_protection_rules
set restrict_push_access = '{}'::uuid[]
where restrict_push_access is null;

alter table git.branch_protection_rules
    alter column restrict_push_access set default '{}'::uuid[],
    alter column restrict_push_access set not null;
