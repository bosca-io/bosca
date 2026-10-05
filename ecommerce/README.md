# Ecommerce

Commerce contracts and implementation for products, carts, orders, payments, shipping,
in-app purchases, and tax. Cross-domain integration uses other Bosca `core-*` contracts.

| Project | Purpose |
| --- | --- |
| `:ecommerce:core-ecommerce` | Shared commerce models and service contracts |
| `:ecommerce:ecommerce` | Repositories, services, GraphQL controllers, and jobs |
| `:ecommerce:payment-bluepay` | BluePay payment integration |
| `:ecommerce:payment-stripe` | Stripe payment integration |
| `:ecommerce:shipping-shippo` | Shippo shipping integration |
| `:ecommerce:iap` | In-app purchase integration |
| `:ecommerce:tax-avalara` | Avalara tax integration |

Run from the workspace root:

```bash
./gradlew :ecommerce:test
./gradlew :ecommerce:core-ecommerce:test :ecommerce:ecommerce:test
```

Dependency versions are in [the shared catalog](../gradle/libs.versions.toml).
The root build resolves local Bosca dependencies to source projects.
