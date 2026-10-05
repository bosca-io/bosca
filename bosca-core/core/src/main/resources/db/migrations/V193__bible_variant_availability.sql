ALTER TABLE bibles
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

WITH ranked AS (
    SELECT metadata_id,
           version,
           variant,
           ROW_NUMBER() OVER (
               PARTITION BY metadata_id, version
               ORDER BY default_variant DESC, variant
           ) AS position
    FROM bibles
)
UPDATE bibles
SET default_variant = ranked.position = 1
FROM ranked
WHERE bibles.metadata_id = ranked.metadata_id
  AND bibles.version = ranked.version
  AND bibles.variant = ranked.variant;

ALTER TABLE bibles
    ADD CONSTRAINT bible_default_variant_enabled
        CHECK (NOT default_variant OR enabled);

CREATE UNIQUE INDEX bibles_one_default_variant
    ON bibles (metadata_id, version)
    WHERE default_variant;
