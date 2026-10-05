-- Push and tag CI now requires EXECUTE on the repository, which EDIT and MANAGE do not imply.
-- One-time backfill: groups holding EDIT or MANAGE when this runs also receive EXECUTE. Grants
-- made afterwards must include EXECUTE explicitly for the group to start builds.
insert into git.repository_permissions (repository_id, group_id, action)
select repository_id, group_id, 'execute'
from git.repository_permissions
where action in ('edit', 'manage')
on conflict do nothing;
