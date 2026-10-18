# Library providers

A component library can publish its Bosca DI registrar classes alongside its renderers. The
consumer compiler composes the registrars for the libraries actually used by its components,
including transitive requirements. It generates direct constructor calls; the consumer does not
scan source or classes and does not list individual library providers.

## Producer declaration

Configure the existing DI and service KSP processors in the library. If their
`ProviderRegistrarPrefix` is `Design`, declare the resulting entry point in the BML library block:

```kotlin
ksp {
    arg("ProviderRegistrarPrefix", "Design")
}

bml {
    packageName.set("example.design.generated")
    library {
        exports.set(listOf("counter", "card"))
        providerRegistrars.set(listOf("bosca.di.DesignProviderRegistrar"))
    }
}
```

Entries name public, top-level classes implementing `bosca.di.ProviderRegistrar` with a public
zero-argument constructor. Declare only registrars owned by this library. Its dependencies advertise
their own registrars. Duplicate names within a declaration or across resolved libraries are errors.
The compiler checks constructor/type access, and publication checks that the registrar classes are
present in the binary JAR.

The library manifest uses format 5, including provider and localization contracts. Build libraries and consumers with matching
BML tooling; earlier manifest formats are rejected before import resolution.

Ordinary constructor-created models need no registrar. An injected model still uses the existing
`<inject>` or `<inject server>` declaration. Live state needs the model's compiled serialization
support, just as a locally declared model does.

## BML Server and host bindings

Generated application pages expose their library registrars to `BmlServer`. During `install`, the
server registers each registrar type once across all pages, before installing page routes. A library
that is imported but never used contributes no registrars. Multiple aliases and diamond dependencies
share the same producer entry points.

The host supplies external services through the ordinary Bosca DI API. For example, if a library
factory depends on its `HostLabels` contract:

```kotlin
val application = BoscaApplication(config)
provides<example.design.HostLabels> { example.design.HostLabels { "Application label" } }

val server = BmlServer(project, example.app.generated.BmlPages.all, port)
server.install(application)
```

Libraries should document their required external bindings and configuration. Missing bindings
remain DI failures. A library registrar cannot silently replace a different host provider: ordinary
duplicate-provider checks still apply.

An intentional replacement belongs after library registration and before requests begin:

```kotlin
server.install(application)
provides<example.design.Counter>(overrideExisting = true) {
    example.design.Counter("Application override")
}
// Start the application's listener after composing its bindings.
```

Hosts that need this ordering can use `install` with their existing Bosca application/listener
lifecycle. During a development hot swap, the server retains existing library registrar types and
their singleton instances; newly introduced types register before the replacement pages become
active. Changes to parent-loaded library dependencies still require the normal development-server
restart described in [getting started](getting-started.md).

## Other hosts

Every compiled module with library providers generates `BmlProviders` in its configured package.
It implements the existing `ProviderRegistrar` interface and exposes its dependency-ordered `all`
list. Standalone project generation, including `bosca bml compile --library`, emits the same
aggregate when its dependencies declare providers. A custom rendering host can compose it once
through the usual registration path:

```kotlin
bosca.di.register(example.app.generated.BmlProviders)
```

Supply external bindings before resolving models and apply intentional overrides afterward. BML
Server already performs this registration for its generated pages, so its host should use the
server installation path above.
