---
name: devflow-report-progress
description: Send a non-terminal PROGRESS_REPORTED callback to the Devflow orchestrator when work is advancing and no blocker or terminal outcome applies
compatibility: opencode
metadata:
  callback: PROGRESS_REPORTED
---

## What this skill does

- Use `devflow_report_progress`
- Keep the run alive
- Send a short human-readable progress summary

## Use this skill when

- work is progressing
- you want the dashboard to show meaningful advancement
- you are not blocked
- you are not done
- you are not failing

## Do not use this skill when

- external information is missing
- the run is complete
- the run has failed

## Required payload

- `summary`

## Optional payload

- `details`

## Good examples

- "Frontend changes done, backend validation running."
- "Repository analysis complete, implementation started."
