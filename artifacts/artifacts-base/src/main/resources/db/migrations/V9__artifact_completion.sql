ALTER TABLE artifacts.versions
    ADD COLUMN completed_commit_sha text
    CHECK (completed_commit_sha ~ '^[0-9a-f]{40}$');

-- Published versions already record their build commit on each publication.
UPDATE artifacts.versions v
SET completed_commit_sha = p.commit_sha
FROM (
    SELECT version_id, min(commit_sha) AS commit_sha
    FROM artifacts.publications
    GROUP BY version_id
    HAVING count(DISTINCT commit_sha) = 1
) p
WHERE v.id = p.version_id
  AND p.commit_sha ~ '^[0-9a-f]{40}$';
