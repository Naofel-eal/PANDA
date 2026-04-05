---
name: devflow-fail-run
description: Send a terminal FAILED callback when the run cannot continue for a technical or execution reason that is not missing external input
compatibility: opencode
metadata:
  callback: FAILED
---

## What this skill does

- Use `devflow_fail_run`
- End the current run in failure
- Provide a concise failure summary

## Use this skill when

- a validation command fails and you cannot resolve it
- the codebase or environment is broken in a way that blocks progress
- the run cannot continue for a technical reason

## Do not use this skill when

- the real problem is missing external information
- the run is actually complete
- the code change is finished but automated tests are absent, unsupported, or no tests match the changed area
- you can describe the remaining risk as a validation gap in `devflow_complete_run`

## Required payload

- `summary`

## Optional payload

- `details`

## Quality bar

- explain the failure precisely
- include the failing command or subsystem in `details` when useful
- reserve this skill for real technical dead-ends, not missing or unavailable tests
- never include source code, diffs, credentials, or secret values in the failure payload
