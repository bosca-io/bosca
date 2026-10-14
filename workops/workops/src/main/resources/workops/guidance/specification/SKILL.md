---
name: workops-specification
description: "Create or refine a specification with independently actionable requirements."
---

# Specification

State the problem, expected behavior, scope, non-goals, integration points, authorization, failure cases, and validation plan. Give each requirement concrete, observable acceptance criteria. Store rich bodies through the content system and populate the linked task's independent description.

Use workops_plan create and add_requirement with a stable requestId for identical retries. Inspect the resulting documents and tasks. Review the plan before implementation; use context, review, diff, and next_action when resuming. Refine the specification as evidence changes.
