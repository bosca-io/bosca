-- ECOM-79: in-app-purchase entitlement redemption with transaction-id replay dedupe.
create type ecom.iap_platform as enum ('android', 'ios');

create table ecom.iap_transactions (
    id              uuid primary key default gen_random_uuid(),
    platform        ecom.iap_platform not null,
    transaction_id  text not null,
    account_id      uuid not null references ecom.accounts (id),
    plan_id         uuid not null references ecom.subscription_plans (id),
    subscription_id uuid references ecom.subscriptions (id),
    product_id      text,
    created         timestamptz not null default now()
);
create unique index iap_transactions_platform_tx_idx on ecom.iap_transactions (platform, transaction_id);
create index iap_transactions_account_idx on ecom.iap_transactions (account_id);

alter table ecom.subscriptions add column external boolean not null default false;
