-- A shipment now carries multiple boxes (parcels), not one container: the packer sorts a center's
-- items into boxes for density and they dispatch together under one shipment. Backfill the new
-- parcels array from each existing shipment's single container_id + lines (one parcel each). The old
-- container_id / lines columns are left in place (deprecated, no longer written) — non-destructive.

alter table ecom.shipments add column parcels jsonb not null default '[]'::jsonb;

update ecom.shipments
   set parcels = jsonb_build_array(
       jsonb_build_object('containerId', container_id, 'lines', coalesce(lines, '[]'::jsonb))
   )
 where parcels = '[]'::jsonb;

-- New inserts no longer supply `lines`; relax its constraint (the column is now deprecated).
alter table ecom.shipments alter column lines drop not null;
