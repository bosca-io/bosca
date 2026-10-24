# Reusable BML libraries and the Bosca BML Design Library

## Outcome

BML projects share compiled components through ordinary Gradle dependencies. Libraries carry their
server rendering, Kotlin types, client behavior, styles, assets and localization. Applications use
those components without copying implementations or registering each renderer.

The Bosca Design Library, `io.bosca:bml-design`, provides the shared controls and visual components
extracted from Git UI, plus document editing, diagrams, charts and dashboard layouts. Git UI and
Notifications Web consume the library while retaining their application behavior.

## Consumer experience

Applications can import a library once in the BML source root's `imports.bml` and use short component
tags. File-local imports and optional aliases are also supported. Local components, imported
components and explicit native HTML/SVG tags follow the documented resolution rules.

Applications choose colors, fonts and spacing in ordinary CSS. Library defaults provide a starting
point; consuming styles can override the documented variables globally or within a themed container.
Controls, overlays and editors use the active theme.

Libraries own reusable presentation and interaction. Applications own their document, routes,
authentication, permissions, data, forms, actions and transport adapters.

See [compiled libraries](libraries.md) for the current Gradle and BML authoring examples,
[component APIs](../bml-design/README.md), and [consumer CSS](../bml-design/css-tokens.md).

## Implementation status

Library artifacts, explicit exports, typed imports, transitive dependencies, runtime composition,
asset linking and localization are implemented in this workspace. Matching source attachments
support original BML and Kotlin navigation. Editable BML project dependencies participate in
development reloads; unsuccessful rebuilds retain the last working generation.

Recorded acceptance includes source-free Maven consumers, real JVM/browser interactions,
macOS arm64 native consumers and IDE fixtures. These records establish their measured scopes;
overall release acceptance remains open for the remaining accessibility, live IDE and runtime
investigations. Implementation detail and dated results belong to the owning requirements.

BML main compilations compile the whole module under every Kotlin compiler execution strategy.
Gradle still tracks inputs and skips unchanged compilations; Kotlin test compilations retain their
normal incremental behavior.

## Requirements and delivery

The linked requirements own the detailed contracts and acceptance for:

1. Library artifacts, source attachments and Maven publication.
2. Project-wide and file-local imports, optional aliases and typed component contracts.
3. Renderer, state, action, deferred and provider composition.
4. Reachable client assets, consumer styles, resources and localization.
5. Development reloads, IDE completion, source navigation and folding.
6. Design Library extraction, component APIs and the independent catalog.
7. Git adoption and reuse in an independent BML application.
8. Integration, packaging, accessibility, documentation and release acceptance.

The producer, import and asset prerequisites have recorded acceptance across their owning
requirements. Extraction and adoption use that capability. The remaining release work stays
separate from completed implementation; it does not require repeating the extraction.

## Acceptance

Use the real compiled artifacts and application integrations to verify source ownership, typed
contracts, independent instances, native semantics, keyboard/focus behavior, consumer CSS and
asset delivery. Keep the original evidence for failures and record the exact scope of successful
checks. Release acceptance follows the linked requirements and existing Bosca artifact workflow.

Select an available dependency version with matching BML tooling. Workspace dependencies resolve
through the root build. Publishing or deploying a release is a separate operation.

## Detailed references

- [Library authoring, imports and assets](libraries.md)
- [Providers and application bindings](library-providers.md)
- [Localization](library-localization.md)
- [Public client types](library-client-types.md)
- [Design Library components](../bml-design/README.md)
- [CSS variables](../bml-design/css-tokens.md)
- [Git migration](../bml-design/migration.md)
- [Independent catalog](../bml-design-catalog/README.md)
- [Binary consumer example and acceptance](../examples/design-library/README.md)
- [IDE support and verification](../bml-ide/README.md)
