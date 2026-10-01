create schema if not exists ecom;

-- ---------------------------------------------------------------------------
-- Enum types (values lowercase snake_case, mapped via EnumMapper in Kotlin)
-- ---------------------------------------------------------------------------

create type ecom.product_type as enum (
    'physical', 'virtual', 'service', 'shipping', 'promotion', 'subscription');
-- 'subscription': the product's configuration binds a plan group; checking out
-- such a line creates an ecom.subscriptions row (plans sell through the cart).

create type ecom.store_type as enum ('virtual', 'physical');

create type ecom.address_type as enum ('billing', 'shipping');

create type ecom.account_type as enum ('consumer', 'business');

create type ecom.payment_type as enum (
    'cash', 'check', 'credit_card', 'account_credit', 'company_credit');

create type ecom.transaction_type as enum (
    'payment', 'refund', 'refund_to_check', 'refund_to_account_credit', 'void');

create type ecom.subscription_status as enum (
    'pending', 'active', 'inactive', 'trialing', 'cancelled',
    'past_due', 'unpaid', 'expired', 'deleted');

create type ecom.subscription_plan_status as enum ('active', 'inactive', 'deleted');

create type ecom.subscription_interval_unit as enum (
    'seconds', 'minutes', 'hours', 'days', 'months', 'years');

create type ecom.promotion_type as enum ('cart', 'subscription');

-- ---------------------------------------------------------------------------
-- Audit -- the single append-only accountability log for the whole module
-- (modernized port of legacy bosca.audit; replaces per-entity history tables
-- AND per-row created_by/modified_by attribution). before/after carry full
-- entity snapshots: before null on create, after null on delete, both null
-- for action-only entries. No FKs by design: the log must outlive and
-- reference anything. Retention = delete by created range.
-- ---------------------------------------------------------------------------

create table ecom.audit (
    id            uuid         not null default gen_random_uuid() primary key,
    principal_id  uuid,                  -- acting principal; null for system/job actions
    profile_id    uuid,                  -- acting profile, when resolvable
    store_id      uuid,                  -- selling context, when applicable
    entity_type   varchar(64)  not null, -- 'cart', 'payment', 'catalog_product', ...
    entity_id     uuid         not null,
    action        varchar(64)  not null, -- 'created', 'price_changed', 'refunded', ...
    before        jsonb,                 -- entity snapshot prior to the change
    after         jsonb,                 -- entity snapshot after the change
    details       jsonb,                 -- optional context for non-mutation entries
    created       timestamptz  not null default now()
);

create index audit_entity_idx on ecom.audit(entity_type, entity_id);
create index audit_store_idx on ecom.audit(store_id);
create index audit_principal_idx on ecom.audit(principal_id);
create index audit_created_idx on ecom.audit(created);

-- ---------------------------------------------------------------------------
-- Companies -- backed by an organization profile (OrganizationService creates
-- the org + profile pair; this row carries only the commerce linkage).
-- ---------------------------------------------------------------------------

create table ecom.companies (
    id               uuid        not null default gen_random_uuid() primary key,
    organization_id  uuid        not null unique,  -- profiles domain organization
    profile_id       uuid        not null unique,  -- the organization's profile
    created          timestamptz not null default now(),
    modified         timestamptz not null default now(),
    deleted          timestamptz
);

-- ---------------------------------------------------------------------------
-- Customers -- backed by a profile (principal -> profile via auth context;
-- principal ids are never stored as ownership keys).
-- ---------------------------------------------------------------------------

create table ecom.customers (
    id                  uuid        not null default gen_random_uuid() primary key,
    company_id          uuid        not null references ecom.companies(id) on delete restrict,
    profile_id          uuid        not null,
    default_account_id  uuid,       -- fk added after accounts
    extras              jsonb       not null default '{}'::jsonb,
    created             timestamptz not null default now(),
    modified            timestamptz not null default now(),
    deleted             timestamptz,
    unique (company_id, profile_id)
);

create index customers_profile_idx on ecom.customers(profile_id) where deleted is null;
create index customers_company_idx on ecom.customers(company_id) where deleted is null;

-- ---------------------------------------------------------------------------
-- Accounts -- the billing entity. Credit balance changes run under
-- select ... for update and write audit entries.
-- ---------------------------------------------------------------------------

create table ecom.accounts (
    id          uuid           not null default gen_random_uuid() primary key,
    company_id  uuid           not null references ecom.companies(id) on delete restrict,
    type        ecom.account_type not null,
    credit      numeric(32, 4) not null default 0 check (credit >= 0),
    extras      jsonb          not null default '{}'::jsonb,
    created     timestamptz    not null default now(),
    modified    timestamptz    not null default now(),
    deleted     timestamptz
);

create index accounts_company_idx on ecom.accounts(company_id) where deleted is null;

alter table ecom.customers
    add constraint customers_default_account_fk
    foreign key (default_account_id) references ecom.accounts(id) on delete set null;

create table ecom.account_customers (
    account_id   uuid not null references ecom.accounts(id) on delete cascade,
    customer_id  uuid not null references ecom.customers(id) on delete cascade,
    created      timestamptz not null default now(),
    primary key (account_id, customer_id)
);

create index account_customers_customer_idx on ecom.account_customers(customer_id);

create table ecom.account_addresses (
    id          uuid         not null default gen_random_uuid() primary key,
    account_id  uuid         not null references ecom.accounts(id) on delete cascade,
    type        ecom.address_type not null,
    preferred   boolean      not null default false,
    address1    varchar(500) not null,
    address2    varchar(500),
    city        varchar(255) not null,
    state       varchar(10)  not null,   -- PORT NOTE: was char(2), widened for intl
    country     char(2)      not null,
    zip         varchar(20)  not null,   -- PORT NOTE: was varchar(10)
    phone       varchar(32)  not null,   -- PORT NOTE: was varchar(10) (US-only)
    note        varchar(500),
    created     timestamptz  not null default now(),
    modified    timestamptz  not null default now()
);

create index account_addresses_account_idx on ecom.account_addresses(account_id);

-- ---------------------------------------------------------------------------
-- Company credits -- gift/store credit instruments. Natural key `number`
-- kept unique; uuid id added for uniform refs. Balance changes run under
-- select ... for update and write audit entries.
-- ---------------------------------------------------------------------------

create table ecom.company_credits (
    id          uuid           not null default gen_random_uuid() primary key,
    company_id  uuid           not null references ecom.companies(id) on delete restrict,
    account_id  uuid           references ecom.accounts(id) on delete set null,
    number      varchar(19)    not null unique,
    description varchar(500),
    balance     numeric(32, 4) not null default 0 check (balance >= 0),
    paid        numeric(32, 4) not null default 0 check (paid >= 0),
    expires     timestamptz,
    created     timestamptz    not null default now(),
    modified    timestamptz    not null default now(),
    deleted     timestamptz
);

create index company_credits_account_idx on ecom.company_credits(account_id) where deleted is null;

-- ---------------------------------------------------------------------------
-- Catalogs & manufacturers
-- ---------------------------------------------------------------------------

create table ecom.catalogs (
    id          uuid         not null default gen_random_uuid() primary key,
    company_id  uuid         not null references ecom.companies(id) on delete restrict,
    key         varchar(255) not null,
    name        varchar(255) not null,
    created     timestamptz  not null default now(),
    modified    timestamptz  not null default now(),
    deleted     timestamptz,
    unique (company_id, key)
);

create table ecom.manufacturers (
    id          uuid         not null default gen_random_uuid() primary key,
    company_id  uuid         not null references ecom.companies(id) on delete restrict,
    name        varchar(255) not null,
    extras      jsonb        not null default '{}'::jsonb,  -- sealed ManufacturerExtras
    created     timestamptz  not null default now(),
    modified    timestamptz  not null default now(),
    deleted     timestamptz
);

create index manufacturers_company_idx on ecom.manufacturers(company_id) where deleted is null;

-- ---------------------------------------------------------------------------
-- Products -- commerce row only; identity/content is a content Metadata
-- document (metadata_id + pinned metadata_version; the pin advances when the
-- document is published through the content workflow). No name/description
-- columns here.
-- ---------------------------------------------------------------------------

create table ecom.products (
    id                uuid              not null default gen_random_uuid() primary key,
    company_id        uuid              not null references ecom.companies(id) on delete restrict,
    manufacturer_id   uuid              not null references ecom.manufacturers(id) on delete restrict,
    manufacturer_sku  varchar(100)      not null,
    metadata_id       uuid              not null unique,  -- content Metadata (no cross-schema FK)
    metadata_version  int               not null default 1,  -- pinned content version; follows content publish
    type              ecom.product_type not null,
    configuration     jsonb             not null default '{}'::jsonb,  -- sealed ProductConfiguration
    weight            float             not null default 1,  -- shipping weight; intrinsic to the product
    created           timestamptz       not null default now(),
    modified          timestamptz       not null default now(),
    deleted           timestamptz,
    unique (manufacturer_id, manufacturer_sku)
);

create index products_company_sku_idx on ecom.products(company_id, manufacturer_sku) where deleted is null;
create index products_metadata_idx on ecom.products(metadata_id);

-- ---------------------------------------------------------------------------
-- Catalog products -- the sellable entry (product placed in a catalog at a
-- price, within an availability window). Price changes write audit entries.
-- For 'subscription' products, price = the signup/first-period charge;
-- renewals use the plan price snapshotted into the subscription.
-- PORT NOTE: legacy had `on delete cascade` to catalog/product; soft delete
-- governs lifecycle now, so FKs are restrict.
-- ---------------------------------------------------------------------------

create table ecom.catalog_products (
    id          uuid              not null default gen_random_uuid() primary key,
    catalog_id  uuid              not null references ecom.catalogs(id) on delete restrict,
    product_id  uuid              not null references ecom.products(id) on delete restrict,
    type        ecom.product_type not null,
    price       numeric(32, 4)    not null,
    taxable     boolean           not null default true,
    starts      timestamptz       not null default now(),
    ends        timestamptz       not null default now() + interval '1000 days',
    promotions  varchar[]         not null default '{}',  -- promotion codes
    extras      jsonb             not null default '{}'::jsonb,  -- sealed CatalogProductExtras
    created     timestamptz       not null default now(),
    modified    timestamptz       not null default now(),
    deleted     timestamptz
);

create index catalog_products_catalog_idx
    on ecom.catalog_products(catalog_id, type, starts, ends) where deleted is null;
create index catalog_products_product_idx
    on ecom.catalog_products(product_id) where deleted is null;

-- ---------------------------------------------------------------------------
-- Providers -- configuration rows; behavior binds via DI provider `key`
-- (legacy provider_class/Class.forName is gone).
-- ---------------------------------------------------------------------------

create table ecom.payment_providers (
    id             uuid         not null default gen_random_uuid() primary key,
    company_id     uuid         not null references ecom.companies(id) on delete restrict,
    name           varchar(255) not null,
    provider_key   varchar(255) not null,  -- DI-registered PaymentProvider key
    configuration  jsonb        not null default '{}'::jsonb,
    created        timestamptz  not null default now(),
    modified       timestamptz  not null default now(),
    deleted        timestamptz
);

create table ecom.shipping_providers (
    id             uuid         not null default gen_random_uuid() primary key,
    company_id     uuid         not null references ecom.companies(id) on delete restrict,
    name           varchar(255) not null,
    key            varchar(255) not null unique,   -- business key (legacy V1_4)
    provider_key   varchar(255) not null,          -- DI-registered ShippingProvider key
    configuration  jsonb        not null default '{}'::jsonb,
    created        timestamptz  not null default now(),
    modified       timestamptz  not null default now(),
    deleted        timestamptz
);

-- ---------------------------------------------------------------------------
-- Stores -- the selling context.
-- PORT NOTE: legacy stores.identifier FK'd minion.instances; now a plain
-- unique storefront key.
-- ---------------------------------------------------------------------------

create table ecom.stores (
    id                           uuid            not null default gen_random_uuid() primary key,
    identifier                   varchar(255)    not null unique,
    name                         varchar(255)    not null,
    company_id                   uuid            not null references ecom.companies(id) on delete restrict,
    catalog_id                   uuid            not null references ecom.catalogs(id) on delete restrict,
    type                         ecom.store_type not null,
    payment_provider_id          uuid            not null references ecom.payment_providers(id) on delete restrict,
    shipping_catalog_product_id  uuid            not null references ecom.catalog_products(id) on delete restrict,
    cart_expiration_seconds      int             not null default 86400,
    created                      timestamptz     not null default now(),
    modified                     timestamptz     not null default now(),
    deleted                      timestamptz
);

-- ---------------------------------------------------------------------------
-- Fulfillment & inventory
-- ---------------------------------------------------------------------------

create table ecom.fulfillment_centers (
    id                    uuid         not null default gen_random_uuid() primary key,
    company_id            uuid         not null references ecom.companies(id) on delete restrict,
    name                  varchar(255) not null,
    connector_key         varchar(255) not null,  -- DI-registered connector SPI key (was connector_class)
    shipping_provider_id  uuid         not null references ecom.shipping_providers(id) on delete restrict,
    address1              varchar(500) not null,
    address2              varchar(500),
    city                  varchar(255) not null,
    state                 varchar(10)  not null,
    country               char(2)      not null,
    zip                   varchar(20)  not null,
    sync_interval_seconds int          not null default 60,
    created               timestamptz  not null default now(),
    modified              timestamptz  not null default now(),
    deleted               timestamptz
);

create table ecom.product_inventory (
    id                     uuid         not null default gen_random_uuid() primary key,
    product_id             uuid         not null references ecom.products(id) on delete restrict,
    fulfillment_center_id  uuid         not null references ecom.fulfillment_centers(id) on delete restrict,
    sku                    varchar(100) not null,
    quantity               int          not null check (quantity >= 0 and quantity >= pending),
    pending                int          not null default 0 check (pending >= 0),
    in_cart                int          not null default 0 check (in_cart >= 0),
    manual_quantity        boolean      not null default false,
    expiration_seconds     int          not null default 86400,  -- in_cart reservation TTL (seconds)
    created                timestamptz  not null default now(),
    modified               timestamptz  not null default now(),
    unique (product_id, fulfillment_center_id, sku)
);
-- available = quantity - pending - in_cart; all transitions under select...for update

create index product_inventory_product_idx on ecom.product_inventory(product_id);

create table ecom.shipping_containers (
    id                uuid        not null default gen_random_uuid() primary key,
    company_id        uuid        not null references ecom.companies(id) on delete restrict,
    width             float       not null,
    height            float       not null,
    length            float       not null,
    weight            float       not null,
    supported_width   float       not null,
    supported_height  float       not null,
    supported_length  float       not null,
    supported_weight  float       not null,
    created           timestamptz not null default now(),
    modified          timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Carts -- items stay a jsonb array of CartItem (legacy design preserved; the
-- platform handles jsonb as JsonElement). Item-level price snapshots
-- (base/retail/sales) are frozen at add-time -- the cart is its own
-- historical record. Totals are denormalized columns maintained by the cart
-- service on every mutation, under select ... for update (legacy
-- getCartForUpdate). status is the combinable CartStatus flag set, stored as
-- an int bitmask (legacy queries: `status & 1 = 1`).
-- ---------------------------------------------------------------------------

create table ecom.carts (
    id                        uuid           not null default gen_random_uuid() primary key,
    company_id                uuid           not null references ecom.companies(id) on delete restrict,
    store_id                  uuid           not null references ecom.stores(id) on delete restrict,
    account_id                uuid           references ecom.accounts(id) on delete set null,
    customer_id               uuid           references ecom.customers(id) on delete set null,
    status                    int            not null default 1,  -- CartStatus bitmask
    items                     jsonb          not null default '[]'::jsonb,  -- CartItem[]
    extras                    jsonb          not null default '{}'::jsonb,
    expires                   timestamptz    not null,
    billing_same_as_shipping  boolean        not null default false,
    -- denormalized totals (Money, scale 4)
    retail_total              numeric(32, 4) not null default 0,
    retail_subtotal           numeric(32, 4) not null default 0,
    sales_total               numeric(32, 4) not null default 0,
    sales_subtotal            numeric(32, 4) not null default 0,
    shipping                  numeric(32, 4) not null default 0,
    tax                       numeric(32, 4) not null default 0,
    discounts                 numeric(32, 4) not null default 0,
    paid                      numeric(32, 4) not null default 0,
    pending_paid              numeric(32, 4) not null default 0,
    due                       numeric(32, 4) not null default 0,
    refund_due                numeric(32, 4) not null default 0,
    quantity                  int            not null default 0,
    created                   timestamptz    not null default now(),
    modified                  timestamptz    not null default now(),
    deleted                   timestamptz
);

create index carts_company_idx on ecom.carts(company_id) where deleted is null;
create index carts_account_idx on ecom.carts(account_id) where deleted is null;
create index carts_customer_idx on ecom.carts(customer_id) where deleted is null;
create index carts_status_expires_idx on ecom.carts(status, expires);  -- expiration sweep

-- Reporting view over the jsonb items array (legacy cart_items view, snake_cased).
create view ecom.cart_items as
select id                                                                  as cart_id,
       (jsonb_array_elements(items) ->> 'id')::uuid                        as id,
       jsonb_array_elements(items) ->> 'type'                              as type,
       ((jsonb_array_elements(items) ->> 'status')::int)                   as status,
       ((jsonb_array_elements(items) ->> 'quantity')::int)                 as quantity,
       ((jsonb_array_elements(items) ->> 'catalogProductId')::uuid)        as catalog_product_id,
       (jsonb_array_elements(items) ->> 'parentId')::uuid                  as parent_id,
       ((jsonb_array_elements(items) ->> 'baseRetailPrice')::numeric(32,4))   as base_retail_price,
       ((jsonb_array_elements(items) ->> 'retailPrice')::numeric(32,4))       as retail_price,
       ((jsonb_array_elements(items) ->> 'retailSubtotal')::numeric(32,4))    as retail_subtotal,
       ((jsonb_array_elements(items) ->> 'salesPrice')::numeric(32,4))        as sales_price,
       ((jsonb_array_elements(items) ->> 'salesSubtotal')::numeric(32,4))     as sales_subtotal,
       ((jsonb_array_elements(items) ->> 'discounts')::numeric(32,4))         as discounts,
       ((jsonb_array_elements(items) ->> 'paid')::numeric(32,4))              as paid,
       (((jsonb_array_elements(items) -> 'taxes') ->> 'taxes')::numeric(32,4)) as taxes,
       ((jsonb_array_elements(items) ->> 'expires')::timestamptz)             as expires,
       jsonb_array_elements(items) -> 'pricingModifiedBy'                  as pricing_modified_by,
       jsonb_array_elements(items) -> 'discountsFrom'                      as discounts_from,
       jsonb_array_elements(items) -> 'promotions'                         as promotions,
       jsonb_array_elements(items) -> 'configuration'                      as configuration
from ecom.carts;

create table ecom.cart_addresses (
    id          uuid              not null default gen_random_uuid() primary key,
    cart_id     uuid              not null references ecom.carts(id) on delete cascade,
    type        ecom.address_type not null,
    first_name  varchar(255)      not null,
    last_name   varchar(255)      not null,
    address1    varchar(500)      not null,
    address2    varchar(500),
    city        varchar(255)      not null,
    state       varchar(10)       not null,
    country     char(2)           not null,
    zip         varchar(20)       not null,
    phone       varchar(32)       not null,
    email       varchar(255),
    note        varchar(500),
    validated   boolean           not null default false,
    created     timestamptz       not null default now(),
    modified    timestamptz       not null default now(),
    unique (cart_id, type)
);

-- ---------------------------------------------------------------------------
-- Payments -- full provider-tracking record. State transitions flip flags and
-- write audit entries; provider evidence columns are never overwritten.
-- ---------------------------------------------------------------------------

create table ecom.payments (
    id                              uuid                  not null default gen_random_uuid() primary key,
    transaction_type                ecom.transaction_type not null,
    type                            ecom.payment_type     not null,
    provider_id                     uuid                  not null references ecom.payment_providers(id) on delete restrict,
    store_id                        uuid                  not null references ecom.stores(id) on delete restrict,
    account_id                      uuid                  references ecom.accounts(id) on delete restrict,
    customer_id                     uuid                  references ecom.customers(id) on delete restrict,
    cart_id                         uuid                  references ecom.carts(id) on delete restrict,
    subscription_id                 uuid,                 -- fk added after subscriptions
    parent_id                       uuid                  references ecom.payments(id) on delete restrict,  -- refund -> original payment
    amount                          numeric(32, 4)        not null default 0,
    non_refundable_amount           numeric(32, 4)        not null default 0,
    refunded_amount                 numeric(32, 4)        not null default 0,
    company_credit_number           varchar(19),
    check_number                    varchar(100),
    check_confirmed                 timestamptz,
    complete                        boolean               not null default false,
    confirmed                       boolean               not null default false,
    voided                          timestamptz,
    voided_reason                   varchar(500),
    refunded                        timestamptz,
    refund_reason                   varchar(500),
    note                            varchar(500),
    email_receipt                   boolean               not null default true,
    email                           varchar(255),
    -- provider evidence (tokens/ids only; PANs are never stored)
    provider_provider_id            varchar(255),
    provider_status                 varchar(255),
    provider_transaction_id         varchar(255),
    provider_avs                    varchar(255),
    provider_type                   varchar(255),
    provider_message                varchar(2000),
    provider_error                  varchar(2000),
    parent_provider_id              varchar(255),
    parent_provider_transaction_id  varchar(255),
    created                         timestamptz           not null default now(),
    modified                        timestamptz           not null default now()
);

create index payments_cart_idx on ecom.payments(cart_id);
create index payments_account_idx on ecom.payments(account_id);
create index payments_subscription_idx on ecom.payments(subscription_id);
create index payments_pending_idx on ecom.payments(complete, confirmed) where not complete;

-- ---------------------------------------------------------------------------
-- Subscriptions
-- PORT NOTE: legacy plan/plan-group ids were caller-supplied varchars with
-- (store, id) composite PKs; now uuid PKs + a per-store unique `key`.
-- Plans sell through the cart: subscriptions carry cart_id provenance when
-- created at checkout (SUBSCRIPTION product lines); subscribe() direct path
-- leaves it null.
-- ---------------------------------------------------------------------------

create table ecom.subscription_plan_groups (
    id               uuid         not null default gen_random_uuid() primary key,
    store_id         uuid         not null references ecom.stores(id) on delete restrict,
    key              varchar(255) not null,
    name             varchar(255) not null,
    description      varchar      not null default '',
    payment_retries  int          not null default 3,
    created          timestamptz  not null default now(),
    modified         timestamptz  not null default now(),
    deleted          timestamptz,
    unique (store_id, key)
);

create table ecom.subscription_plans (
    id             uuid           not null default gen_random_uuid() primary key,
    plan_group_id  uuid           not null references ecom.subscription_plan_groups(id) on delete restrict,
    store_id       uuid           not null references ecom.stores(id) on delete restrict,
    key            varchar(255)   not null,
    name           varchar(255)   not null,
    description    varchar        not null default '',
    status         ecom.subscription_plan_status not null default 'active',
    price          numeric(32, 4) not null,
    interval       int            not null,
    interval_unit  ecom.subscription_interval_unit not null,
    configuration  jsonb          not null default '{}'::jsonb,  -- sealed PlanConfiguration
    expires        timestamptz,
    created        timestamptz    not null default now(),
    modified       timestamptz    not null default now(),
    deleted        timestamptz,
    unique (store_id, key)
);

create table ecom.subscriptions (
    id                    uuid           not null default gen_random_uuid() primary key,
    store_id              uuid           not null references ecom.stores(id) on delete restrict,
    account_id            uuid           not null references ecom.accounts(id) on delete restrict,
    plan_id               uuid           not null references ecom.subscription_plans(id) on delete restrict,
    plan_group_id         uuid           not null references ecom.subscription_plan_groups(id) on delete restrict,
    cart_id               uuid           references ecom.carts(id) on delete set null,  -- originating cart, when subscribed via checkout
    status                ecom.subscription_status not null default 'pending',
    price                 numeric(32, 4) not null,  -- snapshot; plan price may change later
    interval              int            not null,
    interval_unit         ecom.subscription_interval_unit not null,
    renews                timestamptz    not null default now(),
    expires               timestamptz,
    payment_failures      int            not null default 0,
    renewals              int            not null default 0,
    last_payment_id       uuid           references ecom.payments(id) on delete set null,
    next_subscription_id  uuid           references ecom.subscriptions(id) on delete set null,  -- plan migration chain
    extras                jsonb          not null default '{}'::jsonb,  -- sealed SubscriptionExtras (vaulted payment-method ref)
    created               timestamptz    not null default now(),
    modified              timestamptz    not null default now(),
    deleted               timestamptz
);

create index subscriptions_renews_idx on ecom.subscriptions(renews, status);  -- renewal job scan
create index subscriptions_account_idx on ecom.subscriptions(account_id) where deleted is null;

alter table ecom.payments
    add constraint payments_subscription_fk
    foreign key (subscription_id) references ecom.subscriptions(id) on delete restrict;

-- ---------------------------------------------------------------------------
-- Promotions -- natural identity is (store, code); uuid id added for uniform
-- refs, with the natural key unique. Rule edits write audit entries.
-- ---------------------------------------------------------------------------

create table ecom.promotions (
    id        uuid                not null default gen_random_uuid() primary key,
    store_id  uuid                not null references ecom.stores(id) on delete restrict,
    code      varchar(255)        not null,
    name      varchar(255)        not null,
    type      ecom.promotion_type not null,
    rule      jsonb               not null,  -- sealed Rule hierarchy (CartRule | SubscriptionRule)
    starts    timestamptz         not null,
    ends      timestamptz         not null,
    created   timestamptz         not null default now(),
    modified  timestamptz         not null default now(),
    deleted   timestamptz,
    unique (store_id, code)
);

-- redemption-limit counters; updated atomically (single guarded UPDATE)
create table ecom.promotion_availability (
    promotion_id  uuid   not null primary key references ecom.promotions(id) on delete cascade,
    quantity      bigint not null check (quantity >= 0),
    redeemed      bigint not null default 0 check (redeemed >= 0 and redeemed <= quantity)
);

-- append-only redemption audit
create table ecom.promotion_redemptions (
    id            uuid        not null default gen_random_uuid() primary key,
    promotion_id  uuid        not null references ecom.promotions(id) on delete restrict,
    account_id    uuid        not null references ecom.accounts(id) on delete restrict,
    cart_id       uuid        references ecom.carts(id) on delete set null,
    created       timestamptz not null default now()
);

create index promotion_redemptions_promotion_idx on ecom.promotion_redemptions(promotion_id, account_id);

-- per-account promotion state
create table ecom.account_promotions (
    promotion_id  uuid        not null references ecom.promotions(id) on delete cascade,
    account_id    uuid        not null references ecom.accounts(id) on delete cascade,
    extras        jsonb       not null default '{}'::jsonb,
    created       timestamptz not null default now(),
    modified      timestamptz not null default now(),
    primary key (promotion_id, account_id)
);
