ALTER TABLE artifacts.publications
    ADD COLUMN verified_attempt INTEGER;

-- Successful verification writes both timestamps together. Other states need verification again.
UPDATE artifacts.publications
SET verified_attempt = attempts
WHERE verified IS NOT NULL
  AND error IS NULL
  AND modified = verified;
