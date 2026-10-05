ALTER TABLE principals ADD COLUMN primary_profile_id uuid;
ALTER TABLE principals ADD CONSTRAINT fk_principals_primary_profile FOREIGN KEY (primary_profile_id) REFERENCES profiles (id) ON DELETE SET NULL;
