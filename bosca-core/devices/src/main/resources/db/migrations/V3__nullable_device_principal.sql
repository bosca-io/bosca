-- A device and its provider tokens belong to an installation, not to a user account.
-- Signing out clears this association while preserving installation identity and tokens.
alter table devices.devices
    alter column principal_id drop not null;
