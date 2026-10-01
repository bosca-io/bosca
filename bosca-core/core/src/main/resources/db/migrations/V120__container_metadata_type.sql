ALTER TYPE document_template_container_type ADD VALUE 'metadata';
ALTER TABLE document_template_containers ADD COLUMN renderers JSONB;
ALTER TABLE document_template_containers ADD COLUMN filters JSONB;
