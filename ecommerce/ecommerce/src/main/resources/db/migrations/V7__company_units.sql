-- ECOM-SPEC-4 REQ-44: per-company dimension + weight units. The unit a company's product and
-- container dimensions/weights are stored in (the packer compares relative values; carrier providers
-- convert from these at label time). Default inches/pounds — legacy/Shippo parity.

create type ecom.length_unit as enum ('inches', 'centimeters');
create type ecom.weight_unit as enum ('pounds', 'kilograms');

alter table ecom.companies add column length_unit ecom.length_unit not null default 'inches';
alter table ecom.companies add column weight_unit ecom.weight_unit not null default 'pounds';
