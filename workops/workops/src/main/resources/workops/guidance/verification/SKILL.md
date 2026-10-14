---
name: workops-verification
description: "Verify acceptance criteria, test quality, and measured line and branch coverage."
---

# Verification

Check every requirement's explicit acceptance criteria against current documents and linked tasks. Record outcomes PASSED, FAILED, or NOT_RUN with concrete evidence. Passing evidence includes the required TDD phases and a substantive test quality review when the effective policy requires them.

For every affected production scope, provide the coverage command, report path, raw covered and missed line and branch counters, and review of remaining uncovered paths. Derive percentages without rounding. Default policy requires at least 99% line coverage and 97% branch coverage; existing repository gates also apply. Explain reports with no executable branches; reports with no executable lines cannot demonstrate coverage. Passing test counts are not coverage.

Use workops_plan record_validation and verify. Evidence refers to the effective guidance revision, current reviewed interfaces, document fingerprints, and task version; re-record it after these change. Inspect all requirements, generated tasks, and child specifications before completion. Report what ran, what passed, coverage, and any remaining limitations. These tools record caller evidence and cannot certify that commands ran.
