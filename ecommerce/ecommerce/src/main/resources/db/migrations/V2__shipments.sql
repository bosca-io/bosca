-- ECOM-SPEC-3 Phase 1: order fulfillment shipments.
-- A shipment is the per-fulfillment-center physical-fulfillment unit of a paid order. The cart is the
-- order (cart_id is the order key); shipments are created when the cart is paid and shipped later.

create type ecom.shipment_status as enum ('awaiting', 'shipped', 'cancelled');

create table ecom.shipments (
    id                     uuid         not null default gen_random_uuid() primary key,
    cart_id                uuid         not null references ecom.carts(id) on delete restrict,
    store_id               uuid         not null references ecom.stores(id) on delete restrict,
    company_id             uuid         not null references ecom.companies(id) on delete restrict,
    fulfillment_center_id  uuid         not null references ecom.fulfillment_centers(id) on delete restrict,
    status                 ecom.shipment_status not null default 'awaiting',
    lines                  jsonb        not null default '[]'::jsonb,  -- List<ShipmentLine> (inventory rows + qtys to draw down)
    carrier                varchar(255),
    tracking               varchar(255),
    shipped                timestamptz,
    created                timestamptz  not null default now(),
    modified               timestamptz  not null default now()
);

create index shipments_cart_idx on ecom.shipments(cart_id);
create index shipments_store_status_idx on ecom.shipments(store_id, status);  -- admin order/shipment listing
