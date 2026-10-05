-- ECOM-81: per-center inventory-sync bookkeeping. `last_synced` lets the scheduled sweep honor each
-- center's `sync_interval_seconds` (null = never synced, always due).
alter table ecom.fulfillment_centers add column last_synced timestamptz;
