create type attribute_location as enum ('item', 'relationship');

alter table collection_template_attributes add column location attribute_location;