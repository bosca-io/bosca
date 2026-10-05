-- Convert legacy "loose box" parcels (null container) on still-awaiting shipments into `unpacked`
-- items, dropping the loose parcels, then flag those shipments UNABLE_TO_PACKAGE. Dispatched/historical
-- shipments are left untouched.

update ecom.shipments s
   set unpacked = coalesce((
           select jsonb_agg(line)
           from jsonb_array_elements(s.parcels) p, jsonb_array_elements(p -> 'lines') line
           where p ->> 'containerId' is null
       ), '[]'::jsonb),
       parcels = coalesce((
           select jsonb_agg(p) from jsonb_array_elements(s.parcels) p where p ->> 'containerId' is not null
       ), '[]'::jsonb)
 where s.status = 'awaiting'
   and exists (select 1 from jsonb_array_elements(s.parcels) p where p ->> 'containerId' is null);

update ecom.shipments
   set status = 'unable_to_package'::ecom.shipment_status
 where status = 'awaiting' and jsonb_array_length(unpacked) > 0;
