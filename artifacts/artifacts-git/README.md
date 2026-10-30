# Git CI artifact registry integration

Connects Git CI's produced/required artifact extension points to the artifact registry's core service
contract. Select `ArtifactsGitProviderRegistrar` in hosts that compose both domains, including Atlas
and the existing Bosca server/runner. Standalone Git does not depend on this module or load registry
providers. Git's own build-output storage and download routes remain in Git CI.

The adapter resolves protocol coordinates, verifies produced/required versions and tags, and announces
completed versions through the registry service. It depends only on domain contracts at runtime.

Run from the workspace root:

```bash
./gradlew :artifacts:artifacts-git:test
```

Tests include generated provider wiring, protocol coordinate resolution, and real PostgreSQL/NATS
completion dispatch through Git CI and the registry.
