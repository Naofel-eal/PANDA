// @ts-check
import { randomUUID } from "node:crypto"
import { mkdir, readFile, writeFile } from "node:fs/promises"
import { dirname } from "node:path"
import { tool } from "@opencode-ai/plugin"
import { AgentEventType, BlockerType, ContentType, ProcessTiming, RequestedFrom, ResumeTrigger } from "../lib/panda-constants.js"

const blockerTypes = Object.values(BlockerType)
const requestedFromValues = Object.values(RequestedFrom)
const resumeTriggerValues = Object.values(ResumeTrigger)

console.log("[panda-tool] PANDA tool file loaded")

function requiredEnv(name) {
  const value = process.env[name]
  if (!value || !value.trim()) {
    throw new Error(`Missing required environment variable ${name}`)
  }
  return value
}

let _config = null
function config() {
  if (!_config) {
    _config = {
      orchestratorUrl: requiredEnv("PANDA_ORCHESTRATOR_URL"),
      workflowId: requiredEnv("PANDA_WORKFLOW_ID"),
      agentRunId: requiredEnv("PANDA_AGENT_RUN_ID"),
      stateFile: requiredEnv("PANDA_AGENT_STATE_FILE")
    }
    console.log(`[panda-tool] Config resolved for run ${_config.agentRunId} (workflow ${_config.workflowId})`)
  }
  return _config
}

async function readStateFile() {
  const { stateFile, workflowId, agentRunId } = config()
  try {
    const raw = await readFile(stateFile, "utf8")
    return JSON.parse(raw)
  } catch {
    return {
      workflowId,
      agentRunId,
      terminalEventSent: false,
      events: []
    }
  }
}

async function writeStateFile(state) {
  const { stateFile } = config()
  await mkdir(dirname(stateFile), { recursive: true })
  await writeFile(stateFile, JSON.stringify(state, null, 2))
}

async function recordEvent(type, terminal) {
  const state = await readStateFile()
  state.events.push({ type, at: new Date().toISOString() })
  if (terminal) {
    state.terminalEventSent = true
    state.terminalType = type
  }
  await writeStateFile(state)
}

function nonBlankText(value, field) {
  if (typeof value !== "string") {
    throw new Error(`${field} must be a string`)
  }
  const normalized = value.trim().replace(/\s+/g, " ")
  if (!normalized) {
    throw new Error(`${field} must not be blank`)
  }
  return normalized
}

function normalizeReasonCode(value) {
  const normalized = nonBlankText(value, "reasonCode")
  return normalized
    .replace(/[^A-Za-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .toUpperCase()
}

function normalizeDetails(details) {
  if (!details || typeof details !== "object" || Array.isArray(details)) {
    return {}
  }

  const normalized = { ...details }
  if (Array.isArray(normalized.missingInformation)) {
    const missingInformation = normalized.missingInformation
      .filter((item) => typeof item === "string")
      .map((item) => item.trim())
      .filter(Boolean)
      .slice(0, 3)

    if (missingInformation.length > 0) {
      normalized.missingInformation = missingInformation
    } else {
      delete normalized.missingInformation
    }
  }
  return normalized
}

async function sendEvent(type, payload, terminal = false) {
  const { orchestratorUrl, workflowId, agentRunId } = config()
  const body = {
    eventId: `${agentRunId}:${randomUUID()}`,
    workflowId,
    agentRunId,
    type,
    occurredAt: new Date().toISOString(),
    ...payload
  }
  console.log(`[panda-tool] Sending ${type} for run ${agentRunId}`)

  const response = await fetch(`${orchestratorUrl}/internal/agent-events`, {
    method: "POST",
    headers: {
      "content-type": ContentType.JSON
    },
    body: JSON.stringify(body)
  })

  if (!response.ok) {
    throw new Error(`Unable to send ${type} to orchestrator: HTTP ${response.status} ${await response.text()}`)
  }

  await recordEvent(type, terminal)
  console.log(`[panda-tool] Delivered ${type} for run ${agentRunId}`)
  if (terminal) {
    console.log(`[panda-tool] Scheduling process exit after terminal event ${type} for run ${agentRunId}`)
    scheduleProcessExit()
  }
  return `${type} delivered to the orchestrator`
}

function scheduleProcessExit() {
  setTimeout(() => {
    process.exit(0)
  }, ProcessTiming.terminalExitDelayMs).unref?.()
}

export const report_progress = tool({
  description: "Send a non-terminal progress update to the PANDA orchestrator.",
  args: {
    summary: tool.schema.string().describe("Concise current progress summary."),
    details: tool.schema.object({}).passthrough().optional()
  },
  async execute(args) {
    return sendEvent(AgentEventType.PROGRESS_REPORTED, {
      summary: args.summary,
      details: args.details ?? {}
    })
  }
})

export const request_input = tool({
  description: "Stop the current run because external information is required.",
  args: {
    blockerType: tool.schema.enum(blockerTypes),
    reasonCode: tool.schema.string().describe("Stable machine-readable reason code."),
    summary: tool.schema.string().describe("Short explanation of the blocker."),
    requestedFrom: tool.schema.enum(requestedFromValues),
    resumeTrigger: tool.schema.enum(resumeTriggerValues),
    suggestedComment: tool.schema.string().describe("Exact ticket comment the orchestrator should post."),
    details: tool.schema.object({}).passthrough().optional()
  },
  async execute(args) {
    return sendEvent(AgentEventType.INPUT_REQUIRED, {
      blockerType: args.blockerType,
      reasonCode: normalizeReasonCode(args.reasonCode),
      summary: nonBlankText(args.summary, "summary"),
      requestedFrom: args.requestedFrom,
      resumeTrigger: args.resumeTrigger,
      suggestedComment: nonBlankText(args.suggestedComment, "suggestedComment"),
      details: normalizeDetails(args.details)
    }, true)
  }
})

export const complete_run = tool({
  description: "Finish the run successfully and provide its summary and artifacts.",
  args: {
    summary: tool.schema.string().describe("Short final summary for the orchestrator."),
    artifacts: tool.schema.object({}).passthrough().optional(),
    details: tool.schema.object({}).passthrough().optional()
  },
  async execute(args) {
    return sendEvent(AgentEventType.COMPLETED, {
      summary: args.summary,
      artifacts: args.artifacts ?? {},
      details: args.details ?? {}
    }, true)
  }
})

export const fail_run = tool({
  description: "Finish the run in failure when the issue is not missing external input.",
  args: {
    summary: tool.schema.string().describe("Short failure summary."),
    details: tool.schema.object({}).passthrough().optional()
  },
  async execute(args) {
    return sendEvent(AgentEventType.FAILED, {
      summary: args.summary,
      details: args.details ?? {}
    }, true)
  }
})
