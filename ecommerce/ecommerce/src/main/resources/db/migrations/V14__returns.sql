-- Returns / RMAs: the workflow the legacy never had, atop the existing item-refund mechanic.
-- A return is an RMA against a paid order (the cart is the order; cart_id is the order key). Lifecycle
-- requested -> approved -> received -> refunded, with rejected as the admin off-ramp; restock + refund
-- fire at the refunded step. `refund_tender` is also created here (it was a Kotlin-only enum until now).

create type ecom.return_status as enum ('requested', 'approved', 'received', 'refunded', 'rejected');
create type ecom.refund_tender as enum ('original', 'account_credit', 'check');

create table ecom.returns (
    id              uuid               not null default gen_random_uuid() primary key,
    cart_id         uuid               not null references ecom.carts(id) on delete restrict,
    store_id        uuid               not null references ecom.stores(id) on delete restrict,
    company_id      uuid               not null references ecom.companies(id) on delete restrict,
    status          ecom.return_status not null default 'requested',
    reason          text,
    tender          ecom.refund_tender not null default 'original',
    lines           jsonb              not null default '[]'::jsonb,  -- List<ReturnLine> (cart item ids + qtys)
    refunded_amount numeric(32, 4)     not null default 0,
    check_number    text,
    created         timestamptz        not null default now(),
    modified        timestamptz        not null default now(),
    deleted         timestamptz
);

create index returns_cart_idx on ecom.returns(cart_id);
create index returns_store_status_idx on ecom.returns(store_id, status);  -- admin returns listing
