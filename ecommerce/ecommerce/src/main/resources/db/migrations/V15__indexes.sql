-- ECOM-68: indexes for unindexed hot/admin query paths that seq-scan as the data grows.
--   * ecom.payments(created): the date-range reporting query (PaymentRepository.getByDateRange) filters and
--     orders by `created` with no supporting index -> seq scan on a growing append-mostly table.
--   * ecom.shipments(company_id) WHERE status = 'unable_to_package': ShipmentRepository.getUnpackableByCompany
--     runs on every container add/edit; a partial index keeps it to the (usually small) unpackable set.
--   * company_id on the low-cardinality admin config tables (the *ByCompany lookups).

create index if not exists payments_created_idx on ecom.payments (created);

create index if not exists shipments_unpackable_by_company_idx
    on ecom.shipments (company_id)
    where status = 'unable_to_package'::ecom.shipment_status;

create index if not exists stores_company_id_idx              on ecom.stores (company_id);
create index if not exists fulfillment_centers_company_id_idx on ecom.fulfillment_centers (company_id);
create index if not exists shipping_providers_company_id_idx  on ecom.shipping_providers (company_id);
create index if not exists payment_providers_company_id_idx   on ecom.payment_providers (company_id);
create index if not exists shipping_containers_company_id_idx on ecom.shipping_containers (company_id);
