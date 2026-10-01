-- Multi-currency Phase 1 (store-level): a store sells in one ISO-4217 currency, snapshotted onto the
-- records that outlive the store context (cart, payment, subscription) so each always knows its own
-- currency even if the store's later changes. Money stays a pure amount; currency is the boundary
-- attribute. Default 'USD' = single-currency parity for existing rows.

alter table ecom.stores add column currency varchar(3) not null default 'USD';
alter table ecom.carts add column currency varchar(3) not null default 'USD';
alter table ecom.payments add column currency varchar(3) not null default 'USD';
alter table ecom.subscriptions add column currency varchar(3) not null default 'USD';
