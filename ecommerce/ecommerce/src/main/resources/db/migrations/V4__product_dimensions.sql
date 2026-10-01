-- ECOM-SPEC-4 REQ-37: physical shipping dimensions on products (alongside the existing `weight`),
-- so the packer can box for density. 0 = unknown (the packer falls back to a default box for the item).

alter table ecom.products add column width  float not null default 0;
alter table ecom.products add column height float not null default 0;
alter table ecom.products add column length float not null default 0;
