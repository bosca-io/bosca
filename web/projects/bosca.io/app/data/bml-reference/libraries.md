# Compiled BML libraries

BML libraries package reusable components with their compiled Kotlin, client code, styles, assets
and public types. Applications consume their binary dependencies; original sources are attachments
for editing and navigation. The [Bosca Design Library](/bml-reference/design-library) supplies 41 shared
web components through `io.bosca:bml-design`.

## Consume a library

Declare the Gradle dependency using the version selected for the application:

```kotlin
dependencies {
    implementation("com.example:widgets:$widgetsVersion")
}
```

Use the configured Bosca artifact repository for Bosca libraries and select an available release
with matching BML tooling. Within the Bosca workspace, the Design Library dependency is
`implementation(project(":bml:bml-design"))`.

Create `src/main/bml/imports.bml` once:

```bml
<import library="com.example:widgets" />
```

Every page and local component under that BML source root, including nested directories, can use
the library's exported tags directly. An import names `group:artifact`; Gradle selects the version.
Adding the dependency alone does not import its tags. The root imports file accepts imports,
comments and whitespace.

A file can also declare a top-level import before its page or component. File-local imports combine
with root imports. For an optional alias:

```bml
<import library="com.example:widgets" as="widgets" />
<page route="/">
  <html lang="en">
    <body><widgets:notice>Saved preferences</widgets:notice></body>
  </html>
</page>
```

An unaliased import exposes short tags such as `<notice>`. An aliased import exposes only qualified
tags such as `<widgets:notice>`. Importing the same library both ways shares its component identity
and assets; instances keep their ordinary independent state.

## Names and native elements

Local components take precedence over unprefixed library exports. Two different unaliased libraries
exporting the same tag produce a diagnostic; give one an alias. Imports expose only explicitly
exported components, while private helper components remain part of the compiled implementation.
Protected BML syntax such as `page` and `island` retains its meaning.

An imported `button` replaces unqualified `<button>` calls. Use `<html:button>` for the native
element, including inside a component that implements `button`. `svg:` likewise selects native SVG.
For example, after importing the Design Library:

```bml
<button primary>Save</button>
<html:button type="button">Native button</html:button>
```

Use [attribute forwarding](/bml-reference/tag-reference#forwarding-component-attributes) to pass additional
native attributes through a component. Props, projected children and declarative events keep their
BML contracts across the binary dependency.

## Produce a library

Apply the BML Gradle plugin to a Kotlin library project and configure its public exports:

```kotlin
bml {
    packageName.set("example.widgets.generated")
    library {
        exports.set(listOf("notice"))
    }
}
```

The project's group and artifact name define its import identity. Put BML components under
`src/main/bml`, ordinary Kotlin in its Kotlin source set, and client assets under `src/main/client`.
For example:

```bml
<component tag="notice">
  <p class="notice" role="status" {...attrs}><slot /></p>
</component>
```

Keep routable pages, messages and the `application` plugin in a separate application project. The
library's binary JAR carries its compiled renderers, manifest and reachable client/resource
contracts. Its sources JAR includes original BML, Kotlin and authored client sources. Gradle's
`bmlValidateLibrary` checks the binary/source contract and is wired into `check` and library JAR
validation. Maven publication validation checks the actual publication archives as well.

Applications use generated aggregate registries for their selected components and dependencies.
BML Server composes those components and their [library providers](/bml-reference/library-providers); callers
do not manually register every imported renderer. The application owns its document, routes,
authentication, data, forms and actions.

## Styles, assets and public client types

Library styles and client assets participate in the existing BML asset pipeline. Component
reachability selects implementation assets: importing the Design Library module does not add its
source viewer or JSON editor implementation to a page that only renders basic controls.
Fonts and resources are packaged and served from the consuming application's origin.

The Design Library's [CSS variables](/bml-reference/design-tokens) let applications set colors,
fonts, spacing, radii, focus and editor presentation in their own CSS, globally or on a themed
container. The application still chooses its document font, reset and layout.

Publish type-only client event contracts with [library client declarations](/bml-reference/library-client-types).
The consuming linker extracts these declarations from the binary for TypeScript checks; a producer
checkout is unnecessary. Keep search and save transport in application adapters.
[Library localization](/bml-reference/library-localization) contributes component strings to the consuming
application's existing catalog and locale policy.

## Develop and navigate

Run the consuming application's `bmlDev`. Editable direct and transitive BML project libraries
participate in Gradle's input graph: Kotlin, BML, client helpers, CSS, resources, assets, exports and
root imports rebuild the consumer. Successful builds load a new generation in the running server;
failed builds leave the last good generation serving. Framework and ordinary JVM dependency
changes require a restart. See [hot reload](/bml-reference/getting-started#develop-with-hot-reload).

The IntelliJ plugin combines root/file-local imports with the current module's dependency
manifests. Component completion and Go to Declaration support short and aliased tags, including
opening, closing and self-closing uses. Project dependencies navigate to original BML sources;
published dependencies use their own matching attached sources. Embedded Kotlin can navigate to
the library's original model source. Download matching sources through Gradle or attach them in
Project Structure when the IDE reports they are missing or mismatched. Metadata completion remains
available without source attachments. Editing imports invalidates bindings without compiling the
consumer.

Multiline component tags use the same gutter and Collapse/Expand actions as native tags, including
when viewing original BML in read-only library source archives.
