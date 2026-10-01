-- Carry the "this sign-in created the account" fact across the cross-domain OAuth exchange-token hop.
-- The exchange token deliberately persists only the principal id; without this column the account-created
-- signal determined during loginWithThirdParty would be lost before the client redeems the token.
alter table principal_exchange_tokens
    add column account_created boolean not null default false;
