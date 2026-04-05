---
name: devflow-complete-run
description: Send a terminal COMPLETED callback when the local work is finished and the orchestrator can take over publication or workflow progression
compatibility: opencode
metadata:
  callback: COMPLETED
---

## What this skill does

- Use `devflow_complete_run`
- End the current run successfully
- Tell the orchestrator that local work is finished

## Use this skill when

- the information collection phase is complete and implementation can start
- the implementation phase is complete locally
- the requested technical or business rework is finished locally

## Important rule

- In `IMPLEMENTATION`, `COMPLETED` means local work is done
- It does not mean you created branches, commits, pushes, or pull requests
- The orchestrator handles `devflow/...` branches and PR creation after receiving this callback
- Never include source code, diffs, credentials, or secret values in `summary`, `artifacts`, or `details`
- Only send orchestration metadata such as touched repositories, changed file paths, validation commands, and short notes

## Required payload

- `summary`

## Optional payload

- `artifacts`
- `details`

## Useful artifacts

- `artifacts.repoChanges`
- `artifacts.changedFiles`
- `artifacts.validationCommands`
- `artifacts.followUpNotes`
