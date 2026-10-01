-- Verified-email uniqueness backstop (REQ-7): one verified email maps to exactly one principal.
-- The registry is ENFORCEMENT-ONLY — profile_attributes remains the live source of truth for lookups
-- (getPrincipalByEmail). The PRIMARY KEY on email is the backstop behind requireEmailAvailableForSignup.
-- Emails are stored normalized (lower + trimmed) so whitespace/case never produces a spurious second row.
create table principal_emails (
    email     varchar not null primary key,
    principal uuid    not null references principals (id) on delete cascade
);

create index ix_principal_emails_principal on principal_emails (principal);

-- Dedup-safe backfill: DISTINCT ON guarantees one row per normalized email, so the PK can never be
-- violated even on dirty data (the exact situation this feature exists to remediate). Pre-existing
-- duplicate principals beyond the first remain unregistered and are cleaned up via the admin merge
-- (security.admin.mergePrincipals); new second-claimants hit the PK and are rejected going forward.
insert into principal_emails (email, principal)
select distinct on (lower(trim(pa.attributes ->> 'email')))
       lower(trim(pa.attributes ->> 'email')) as email,
       p.id                                   as principal
from profile_attributes pa
         join profiles pr on pr.id = pa.profile
         join principals p on p.id = pr.principal
where pa.type_id = 'bosca.profiles.email'
  and p.verified = true
  and coalesce(trim(pa.attributes ->> 'email'), '') <> ''
order by lower(trim(pa.attributes ->> 'email')), p.created
on conflict (email) do nothing;
