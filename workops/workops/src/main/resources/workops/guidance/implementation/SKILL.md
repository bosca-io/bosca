---
name: workops-implementation
description: "Implement reviewed WorkOps requirements through measured test driven increments."
---

# Implementation

Work one requirement at a time and keep its task status current. For each behavioral increment: RED — write a high quality behavior test and run it to observe the intended missing behavior; GREEN — implement the smallest change that passes; REFACTOR — improve the implementation and rerun the relevant tests. A compile or infrastructure error is not the intended RED failure.

Test outcomes and behavior rather than mirroring private implementation. Cover boundaries, branches, failure paths, permissions, conflicts, cancellation, concurrency where relevant, and realistic integrations. Wiring-heavy behavior needs a real integration test, normally with Testcontainers. Assertions must distinguish correct behavior from plausible incorrect implementations.

Record actual commands and observations for RED, GREEN, and REFACTOR. If a failure appears, investigate it and fix failures caused by the change. Do not lower gates, exclude production paths, or pad assertions to satisfy coverage. Do not commit, push, or publish unless requested.
