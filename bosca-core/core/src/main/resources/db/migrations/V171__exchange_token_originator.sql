-- Carry the caller-supplied login request originator across the cross-domain OAuth exchange-token hop so the
-- redeeming client can be echoed back the originator it started the sign-in with. Nullable: most exchange
-- tokens carry no originator.
alter table principal_exchange_tokens
    add column originator varchar;
