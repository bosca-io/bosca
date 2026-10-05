-- ECOM-78: per-account promotion redemption limit over a frequency window.
create type ecom.frequency_limit as enum ('forever', 'daily', 'weekly', 'monthly', 'yearly');
alter table ecom.promotions add column per_account_limit bigint            not null default 0;
alter table ecom.promotions add column frequency_limit   ecom.frequency_limit not null default 'forever';
create index if not exists promotion_redemptions_promo_account_idx on ecom.promotion_redemptions (promotion_id, account_id, created);
