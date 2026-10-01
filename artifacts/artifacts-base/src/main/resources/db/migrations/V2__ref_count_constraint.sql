-- Prevent reference count from going negative, which would cause premature
-- blob deletion via the deleteIfUnreferenced query (ref_count <= 0).
ALTER TABLE artifacts.blobs ADD CONSTRAINT blobs_ref_count_non_negative CHECK (ref_count >= 0);
