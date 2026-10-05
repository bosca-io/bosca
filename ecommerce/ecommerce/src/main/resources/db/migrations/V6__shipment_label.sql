-- ECOM-SPEC-4 REQ-40: a shipped box records the carrier label URL (alongside carrier + tracking).
alter table ecom.shipments add column label_url varchar(1000);
