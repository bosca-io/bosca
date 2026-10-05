ALTER TABLE backups DROP CONSTRAINT backups_metadata_id_fkey;
ALTER TABLE backups ADD CONSTRAINT backups_metadata_id_fkey FOREIGN KEY (metadata_id) REFERENCES metadata(id) ON DELETE CASCADE;