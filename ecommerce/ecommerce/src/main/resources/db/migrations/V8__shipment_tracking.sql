-- Shipment carrier-tracking lifecycle: extend the single ecom.shipment_status timeline with the
-- carrier-reported states, and record the carrier's raw status wording + a delivery timestamp.
-- (One lifecycle, not a second enum — the carrier advances the same status forward.)

alter type ecom.shipment_status add value if not exists 'in_transit' after 'shipped';
alter type ecom.shipment_status add value if not exists 'out_for_delivery' after 'in_transit';
alter type ecom.shipment_status add value if not exists 'delivered' after 'out_for_delivery';
alter type ecom.shipment_status add value if not exists 'returned' after 'delivered';
alter type ecom.shipment_status add value if not exists 'failure' after 'returned';

alter table ecom.shipments add column carrier_status text;
alter table ecom.shipments add column delivered timestamptz;
