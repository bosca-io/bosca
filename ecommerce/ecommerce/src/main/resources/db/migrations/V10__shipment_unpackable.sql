-- No more "loose box": items that fit no container are tracked as `unpacked` and the shipment is
-- flagged UNABLE_TO_PACKAGE. (The enum value is added here, committed, then USED by V11 — Postgres
-- forbids using a newly-added enum value in the same transaction.)

alter type ecom.shipment_status add value if not exists 'unable_to_package';

alter table ecom.shipments add column unpacked jsonb not null default '[]'::jsonb;
