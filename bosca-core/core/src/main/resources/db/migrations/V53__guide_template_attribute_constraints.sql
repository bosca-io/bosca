-- Add database constraints for guide template attributes to ensure data integrity

-- Ensure key is not empty and follows valid format
alter table guide_template_attributes 
add constraint guide_template_attr_key_not_empty 
check (trim(key) != '');

alter table guide_template_attributes 
add constraint guide_template_attr_key_format 
check (key ~ '^[a-zA-Z0-9_.-]+$');

-- Ensure name is not empty
alter table guide_template_attributes 
add constraint guide_template_attr_name_not_empty 
check (trim(name) != '');

-- Ensure supplementary_key is not empty when provided
alter table guide_template_attributes 
add constraint guide_template_attr_supplementary_key_not_empty 
check (supplementary_key IS NULL OR trim(supplementary_key) != '');

-- Ensure type is a valid attribute type
alter table guide_template_attributes 
add constraint guide_template_attr_type_valid 
check (type IN ('string', 'int', 'float', 'date', 'datetime', 'profile', 'metadata', 'collection'));

-- Ensure ui is a valid UI type
alter table guide_template_attributes 
add constraint guide_template_attr_ui_valid 
check (ui IN ('input', 'textarea', 'image', 'profile', 'collection', 'metadata', 'file'));