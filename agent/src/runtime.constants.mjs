function positiveIntegerFromEnv(name, fallback) {
  const rawValue = process.env[name]
  if (!rawValue) {
    return fallback
  }

  const parsed = Number.parseInt(rawValue, 10)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}

export const HTTP = Object.freeze({
  defaultPort: 8081,
  eventTimeoutMs: 10_000,
  cancelKillDelayMs: 5_000,
  logTailMaxChars: 16_000,
  maxRunDurationMinutes: positiveIntegerFromEnv("AGENT_MAX_RUN_DURATION_MINUTES", 15),
  get maxRunDurationMs() {
    return this.maxRunDurationMinutes * 60 * 1_000
  }
})

export const AgentCommandType = Object.freeze({
  START_RUN: "START_RUN",
  CANCEL_RUN: "CANCEL_RUN"
})

export const AgentEventType = Object.freeze({
  RUN_STARTED: "RUN_STARTED",
  PROGRESS_REPORTED: "PROGRESS_REPORTED",
  INPUT_REQUIRED: "INPUT_REQUIRED",
  COMPLETED: "COMPLETED",
  FAILED: "FAILED",
  CANCELLED: "CANCELLED"
})

export const ProcessSignal = Object.freeze({
  SIGTERM: "SIGTERM",
  SIGKILL: "SIGKILL"
})

export const ProviderRunRef = Object.freeze({
  prefix: "opencode:"
})

export const ContentType = Object.freeze({
  JSON: "application/json"
})

export const Host = Object.freeze({
  bindAll: "0.0.0.0"
})

export const RuntimeConfig = Object.freeze({
  openCodeBinary: "opencode",
  agentName: "nud",
  templateDir: "/agent/opencode",
  workspaceRoot: "/workspace/runs",
  allowedRoot: "/workspace",
  stateRoot: "/var/lib/nud-agent/runs",
  orchestratorUrl: "http://orchestrator:8080"
})

export const EnvironmentName = Object.freeze({
  nudOrchestratorUrl: "NUD_ORCHESTRATOR_URL",
  nudWorkflowId: "NUD_WORKFLOW_ID",
  nudAgentRunId: "NUD_AGENT_RUN_ID",
  nudAgentStateFile: "NUD_AGENT_STATE_FILE",
  nudAgentPhase: "NUD_AGENT_PHASE",
  nudAgentObjective: "NUD_AGENT_OBJECTIVE",
  openCodeModel: "OPENCODE_MODEL",
  openCodeSmallModel: "OPENCODE_SMALL_MODEL",
  openAiApiKey: "OPENAI_API_KEY",
  anthropicApiKey: "ANTHROPIC_API_KEY",
  geminiApiKey: "GEMINI_API_KEY",
  githubToken: "GITHUB_TOKEN",
  openCodeEnableExa: "OPENCODE_ENABLE_EXA"
})
