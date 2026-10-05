-- ECOM-SPEC-4 REQ-39: a shipment is now a packed box, so it references the container it was packed into.
-- Nullable: a loose box (oversize item, or no container catalog) has no container.

alter table ecom.shipments add column container_id uuid references ecom.shipping_containers(id) on delete restrict;
