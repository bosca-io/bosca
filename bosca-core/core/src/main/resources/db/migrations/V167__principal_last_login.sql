alter table principals add column last_login timestamp with time zone;

-- Backfill from the refresh-token history that getPrincipalLastLogin previously
-- derived last login from. Tokens are deleted on expiry/sign-out, so this only
-- recovers what is still known; principals with no surviving tokens stay null.
update principals p
set last_login = rt.last_created
from (
    select principal_id, max(created) as last_created
    from principal_refresh_tokens
    group by principal_id
) rt
where rt.principal_id = p.id;
