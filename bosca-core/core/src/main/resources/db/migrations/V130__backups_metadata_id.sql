ALTER TABLE backups ADD COLUMN metadata_id uuid REFERENCES metadata(id);
