-- Multi-currency anchor refinement: currency belongs on the Catalog (the price book), not the Store.
-- A store sells exactly one catalog, so a store's currency is just its catalog's — keeping it on both
-- duplicates it and invites drift. Move the anchor: drop stores.currency (added in V12), add
-- catalogs.currency. Carts/payments/subscriptions keep their snapshotted currency (V12); it is now
-- sourced from the store's catalog at creation.

alter table ecom.stores drop column currency;
alter table ecom.catalogs add column currency varchar(3) not null default 'USD';
