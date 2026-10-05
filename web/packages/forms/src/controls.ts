import type { Component } from 'vue'

/**
 * Registry mapping control type names to Vue components.
 * Consumers can register custom controls before rendering forms.
 */
const registry = new Map<string, Component>()

/**
 * Registers a Vue component for a given control type name.
 * If a control with the same name already exists, it is replaced.
 *
 * @param name - The control type name (e.g. "text-input", "my-custom-widget")
 * @param component - The Vue component to render for this control type
 */
export function registerControl(name: string, component: Component): void {
  registry.set(name, component)
}

/**
 * Retrieves the Vue component registered for a control type name.
 *
 * @param name - The control type name
 * @returns The registered component, or undefined if not found
 */
export function getControl(name: string): Component | undefined {
  return registry.get(name)
}

/**
 * Returns all registered control names.
 */
export function getRegisteredControls(): string[] {
  return Array.from(registry.keys())
}
