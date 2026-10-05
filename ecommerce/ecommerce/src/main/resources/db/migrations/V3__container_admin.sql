-- ECOM-SPEC-4 REQ-36: make the orphaned ecom.shipping_containers a managed box catalog.
-- Adds a human name and soft-delete to the box type, so admins can curate the containers the packer fills.

alter table ecom.shipping_containers add column name varchar(255) not null default '';
alter table ecom.shipping_containers add column deleted timestamptz;
