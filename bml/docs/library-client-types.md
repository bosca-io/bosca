# Client event types in compiled libraries

An authored `.d.ts` file under `src/main/client` is included in both the library's binary JAR and
its source attachment. Declare a module with a producer-owned name for stable type-only imports:

```ts
declare module "@example/design/select" {
  export interface Selection {
    value: string;
    label: string;
  }
}
```

The file is the authoritative contract. Library helpers and consuming client scripts both use
`import type { Selection } from "@example/design/select"`. Use distinct module names for distinct
libraries. These are TypeScript ambient modules; normal TypeScript declaration rules apply.
Keep their referenced types in the shipped declarations or in dependencies the consumer explicitly
provides. Declarations do not publish executable imports or install npm dependencies.

The existing client manifest inventories each declaration and its digest. When a component from
the library is selected, the linker verifies its archive bytes and extracts declarations beside
the existing ESM inputs under the generated client tree. No producer checkout or source attachment
is needed. Removing that library from the selected components removes its linked declarations.
Declarations are excluded from browser entries and served application resources.

Run `bmlPrepareClient` before type checking and include `build/generated/bml/ts/**/*.ts` in the
consumer's `tsconfig.json`, alongside its other client sources. No paths mapping to the library's
source or generated hash directory is required. Use `import type` so the TypeScript import is
erased during browser bundling. The regular Gradle dependency and BML import select the library;
there is no separate type artifact.

The [design library](../bml-design/README.md) supplies `SelectOption`, `SelectPage` and `SelectSearch`
through `@bosca/bml-design/select`. The [independent consumer](../examples/design-library/README.md)
checks its actual generated adapter and invalid event payloads. The Gradle publication fixture also
type checks and bundles an application after deleting its producer and source JAR.
