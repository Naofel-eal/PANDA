---
name: nud-request-input
description: Send a terminal INPUT_REQUIRED callback when external information is missing and the orchestrator must block the workflow and comment on the ticket
compatibility: opencode
metadata:
  callback: INPUT_REQUIRED
---

## What this skill does

- Use `nud_request_input`
- End the current run
- Ask the orchestrator to block the workflow
- Provide the exact ticket comment the orchestrator should post

## Use this skill when

- a business clarification is missing
- technical review context is missing
- repository mapping is missing
- you cannot continue without external input

## Do not use this skill when

- the issue is purely technical and should be reported as a failure
- you can make a reasonable local decision and continue
- the run is already complete
- the ticket already has enough detail for a reasonable implementation hypothesis after exploring the local workspace

## Required payload

- `blockerType`
- `reasonCode`
- `summary`
- `requestedFrom`
- `resumeTrigger`
- `suggestedComment`

## Optional payload

- `details`

## Quality bar

- `reasonCode` must be stable and machine-readable
- `summary` must explain the blocker in one short paragraph
- `suggestedComment` must be short, precise, and directly actionable
- never leave any required field blank
- ask for one concrete clarification at a time, not a broad discovery questionnaire
- Never paste repository code, diffs, credentials, or secrets into the blocker payload
