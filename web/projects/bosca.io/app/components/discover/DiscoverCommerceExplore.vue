<script setup lang="ts">
// The Commerce marketing page family. Rendered as link cards on every page in
// the family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/commerce',
    icon: 'store',
    title: 'Commerce overview',
    sub: 'A full commerce engine, built into the platform.'
  },
  {
    to: '/discover/commerce/catalog',
    icon: 'tag',
    title: 'Catalog & pricing',
    sub: 'Stores, products, pricing, and promotions.'
  },
  {
    to: '/discover/commerce/orders',
    icon: 'credit-card',
    title: 'Carts & orders',
    sub: 'Checkout, refunds, returns, and store credit.'
  },
  {
    to: '/discover/commerce/subscriptions',
    icon: 'repeat',
    title: 'Subscriptions',
    sub: 'Plans sold in the cart, renewed on schedule.'
  },
  {
    to: '/discover/commerce/fulfillment',
    icon: 'package',
    title: 'Fulfillment',
    sub: 'Inventory, packing suggestions, labels, and tracking.'
  }
]

withDefaults(defineProps<{
  title?: string
}>(), {
  title: 'Keep exploring'
})

const route = useRoute()
const siblings = computed(() => PAGES.filter(p => p.to !== route.path))
</script>

<template>
  <section class="section explore">
    <div class="section-head reveal">
      <p class="kicker">
        {{ title }}
      </p>
      <h2>More on <em>Commerce</em></h2>
    </div>
    <div class="explore-grid reveal">
      <NuxtLink
        v-for="page in siblings"
        :key="page.to"
        :to="page.to"
        class="explore-card"
      >
        <span class="explore-icon">
          <Icon
            :name="page.icon"
            :size="16"
          />
        </span>
        <span class="explore-text">
          <h3>{{ page.title }}</h3>
          <p>{{ page.sub }}</p>
        </span>
        <Icon
          name="arrow-right"
          :size="15"
          class="explore-arrow"
        />
      </NuxtLink>
    </div>
  </section>
</template>
