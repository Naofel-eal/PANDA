import { randomUUID } from "node:crypto"
import { spawn } from "node:child_process"
import { chmod, copyFile, cp, mkdir, readFile, rm, stat, writeFile } from "node:fs/promises"
import http from "node:http"
import path from "node:path"
import {
  AgentCommandType,
  AgentEventType,
  ContentType,
  EnvironmentName,
  Host,
  HTTP,
  ProcessSignal,
  ProviderRunRef,
  RuntimeConfig
} from "./runtime.constants.mjs"

const config = {
  port: Number(process.env.PORT ?? String(HTTP.defaultPort)),
  orchestratorUrl: process.env[EnvironmentName.pandaOrchestratorUrl]?.trim() ?? RuntimeConfig.orchestratorUrl
}

let activeRun = null

function json(response, statusCode, payload) {
  response.writeHead(statusCode, { "content-type": ContentType.JSON })
  response.end(JSON.stringify(payload))
}

async function readJson(request) {
  const chunks = []
  for await (const chunk of request) {
    chunks.push(chunk)
  }
  if (chunks.length === 0) {
    return {}
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"))
}

function stringValue(value) {
  return typeof value === "string" ? value.trim() : ""
}

function resolveExecutionConfig(command) {
  const execution = command?.execution ?? {}
  return {
    model: stringValue(execution.model),
    smallModel: stringValue(execution.smallModel),
    openAiApiKey: stringValue(execution.openAiApiKey),
    anthropicApiKey: stringValue(execution.anthropicApiKey),
    geminiApiKey: stringValue(execution.geminiApiKey),
    copilotGithubToken: stringValue(execution.copilotGithubToken)
  }
}

function buildPrompt(command) {
  const phaseDirectives = buildPhaseDirectives(command)
  return [
    "PANDA run",
    `workflowId: ${command.workflowId}`,
    `agentRunId: ${command.agentRunId}`,
    `phase: ${command.phase}`,
    `objective: ${command.objective}`,
    "",
    "Input snapshot JSON:",
    JSON.stringify(command.inputSnapshot ?? {}, null, 2),
    "",
    ...phaseDirectives
  ].join("\n")
}

function buildPhaseDirectives(command) {
  const snapshot = command?.inputSnapshot ?? {}
  const repositories = Array.isArray(snapshot.repositories) ? snapshot.repositories.filter(Boolean) : []

  if (command?.phase === "TECHNICAL_VALIDATION") {
    const repository = snapshot?.codeChange?.repository
    if (typeof repository === "string" && repository.trim()) {
      return [`Repository in scope: ${repository.trim()}`]
    }
    if (repositories.length === 1) {
      return [`Repository in scope: ${repositories[0]}`]
    }
    return []
  }

  if (repositories.length === 1) {
    return [
      "Repository scope:",
      `- The only repository in scope for this run is ${repositories[0]}.`
    ]
  }

  return []
}

function buildArgs(command, executionConfig) {
  const args = ["run", "--agent", RuntimeConfig.agentName, "--format", "json", "--print-logs", "--log-level", "INFO"]
  if (executionConfig.model) {
    args.push("--model", executionConfig.model)
  }
  args.push(buildPrompt(command))
  return args
}

function resolveProjectDir(command) {
  const candidate = command?.inputSnapshot?.workspace?.projectRoot ?? RuntimeConfig.workspaceRoot
  const resolved = path.resolve(candidate)
  if (!resolved.startsWith(path.resolve(RuntimeConfig.allowedRoot))) {
    throw new Error(`Resolved project directory ${resolved} is outside ${RuntimeConfig.allowedRoot}`)
  }
  return resolved
}

async function createStateFile(command) {
  const stateFile = path.join(RuntimeConfig.stateRoot, `${command.agentRunId}.json`)
  await mkdir(path.dirname(stateFile), { recursive: true })
  await writeFile(stateFile, JSON.stringify({
    workflowId: command.workflowId,
    agentRunId: command.agentRunId,
    terminalEventSent: false,
    events: []
  }, null, 2))
  return stateFile
}

function applyExecutionConfig(env, executionConfig) {
  setOptionalEnv(env, EnvironmentName.openCodeModel, executionConfig.model)
  setOptionalEnv(env, EnvironmentName.openCodeSmallModel, executionConfig.smallModel)
  setOptionalEnv(env, EnvironmentName.openAiApiKey, executionConfig.openAiApiKey)
  setOptionalEnv(env, EnvironmentName.anthropicApiKey, executionConfig.anthropicApiKey)
  setOptionalEnv(env, EnvironmentName.geminiApiKey, executionConfig.geminiApiKey)
  setOptionalEnv(env, EnvironmentName.githubToken, executionConfig.copilotGithubToken)
}

function setOptionalEnv(env, name, value) {
  if (value) {
    env[name] = value
    return
  }
  delete env[name]
}

async function prepareOpenCodeEnvironment(projectDir, executionConfig, phase) {
  const env = { ...process.env }
  const runtimeId = randomUUID()
  const runtimeRoot = path.join(RuntimeConfig.stateRoot, "opencode-runtime", runtimeId)
  const runtimeHome = path.join(runtimeRoot, "home")
  const runtimeBinDir = path.join(runtimeRoot, "bin")
  const homeConfigDir = path.join(runtimeHome, ".config", "opencode")
  const homeNodeModulesCache = path.join(RuntimeConfig.stateRoot, "opencode-home-node-modules-cache")
  const sharedNpmCache = path.join(RuntimeConfig.stateRoot, "npm-cache")

  await mkdir(homeConfigDir, { recursive: true })
  await mkdir(runtimeBinDir, { recursive: true })
  await mkdir(sharedNpmCache, { recursive: true })

  // Restore cached HOME node_modules to skip opencode plugin npm install
  try {
    await stat(homeNodeModulesCache)
    await cp(homeNodeModulesCache, path.join(homeConfigDir, "node_modules"), { recursive: true })
    console.log("[agent-runtime] Restored cached HOME node_modules — skipping plugin install")
  } catch {
    // No cache yet; opencode will install on first run
  }

  // Share npm cache across runs to avoid re-downloading packages
  env.NPM_CONFIG_CACHE = sharedNpmCache
  env.HOME = runtimeHome
  env.PATH = `${runtimeBinDir}:${env.PATH ?? ""}`
  applyExecutionConfig(env, executionConfig)
  await installBlockedNetworkCommands(runtimeBinDir)
  await materializeWorkspaceProject(projectDir, executionConfig, phase)
  return { env, homeConfigDir }
}

async function installBlockedNetworkCommands(runtimeBinDir) {
  const blockedCommands = ["curl", "wget", "gh"]
  const script = [
    "#!/usr/bin/env bash",
    "echo 'PANDA agent policy: external network commands are disabled for this run.' >&2",
    "exit 64"
  ].join("\n")

  for (const command of blockedCommands) {
    const target = path.join(runtimeBinDir, command)
    await writeFile(target, `${script}\n`)
    await chmod(target, 0o755)
  }
}

async function materializeWorkspaceProject(projectDir, executionConfig, phase) {
  const workspaceConfigDir = path.join(projectDir, ".opencode")
  const workspaceConfigFile = path.join(projectDir, "opencode.json")
  const workspaceAgentsFile = path.join(projectDir, "AGENTS.md")
  const workspaceNodeModules = path.join(workspaceConfigDir, "node_modules")
  const nodeModulesCache = path.join(RuntimeConfig.stateRoot, "opencode-node-modules-cache")

  await mkdir(projectDir, { recursive: true })
  await rm(workspaceAgentsFile, { force: true })
  await rm(workspaceConfigFile, { force: true })

  // Save node_modules to shared cache before deleting .opencode
  try {
    await stat(workspaceNodeModules)
    await rm(nodeModulesCache, { recursive: true, force: true })
    await cp(workspaceNodeModules, nodeModulesCache, { recursive: true })
    console.log("[agent-runtime] Cached node_modules for reuse")
  } catch {
    // node_modules not present yet — nothing to cache
  }

  await rm(workspaceConfigDir, { recursive: true, force: true })
  await cp(path.join(RuntimeConfig.templateDir, ".opencode"), workspaceConfigDir, { recursive: true })

  // Restore cached node_modules to skip npm reification
  try {
    await stat(nodeModulesCache)
    await cp(nodeModulesCache, workspaceNodeModules, { recursive: true })
    console.log("[agent-runtime] Restored cached node_modules — skipping npm install")
  } catch {
    // No cache yet; opencode will install on first run
  }

  await copyFile(path.join(RuntimeConfig.templateDir, "opencode.json"), workspaceConfigFile)
  await applyWorkspaceModelConfig(workspaceConfigFile, executionConfig, phase)
}

async function applyWorkspaceModelConfig(workspaceConfigFile, executionConfig, phase) {
  const raw = await readFile(workspaceConfigFile, "utf8")
  const config = JSON.parse(raw)
  if (executionConfig.model) {
    config.model = executionConfig.model
  } else {
    delete config.model
  }
  if (executionConfig.smallModel) {
    config.small_model = executionConfig.smallModel
  } else {
    delete config.small_model
  }

  const agentConfig = config.agent?.[RuntimeConfig.agentName]
  const permissions = agentConfig?.permission
  if (permissions) {
    const isReadOnlyPhase = phase === "INFORMATION_COLLECTION"
    permissions.edit = isReadOnlyPhase ? "deny" : "allow"
    permissions.write = isReadOnlyPhase ? "deny" : "allow"
  }

  await writeFile(workspaceConfigFile, `${JSON.stringify(config, null, 2)}\n`)
}

async function readStateFile(stateFile) {
  try {
    const raw = await readFile(stateFile, "utf8")
    return JSON.parse(raw)
  } catch {
    return {
      terminalEventSent: false,
      events: []
    }
  }
}

async function markTerminalState(stateFile, eventType) {
  const state = await readStateFile(stateFile)
  state.terminalEventSent = true
  state.terminalType = eventType
  state.events = [...(state.events ?? []), { type: eventType, at: new Date().toISOString() }]
  await writeFile(stateFile, JSON.stringify(state, null, 2))
}

async function postEvent(event) {
  const response = await fetch(`${config.orchestratorUrl}/internal/agent-events`, {
    method: "POST",
    headers: {
      "content-type": ContentType.JSON
    },
    body: JSON.stringify(event),
    signal: AbortSignal.timeout(HTTP.eventTimeoutMs)
  })

  if (!response.ok) {
    throw new Error(`Orchestrator rejected event ${event.type}: HTTP ${response.status} ${await response.text()}`)
  }
}

function bufferStream(run, field, text) {
  const value = `${run[field]}${text}`
  run[field] = value.length > HTTP.logTailMaxChars ? value.slice(-HTTP.logTailMaxChars) : value
}

function logStdoutLine(run, line) {
  const trimmed = line.trim()
  if (!trimmed) return
  try {
    const entry = JSON.parse(trimmed)
    if (entry.type === "text") {
      const preview = String(entry.text ?? "").slice(0, 200).replace(/\n/g, " ")
      if (preview) console.log(`[agent:${run.agentRunId.slice(0, 8)}] [text] ${preview}`)
      return
    }
    if (entry.type === "tool_use") {
      const toolName = entry.part?.tool ?? entry.name ?? "?"
      const input = entry.part?.state?.input ?? entry.input ?? {}
      const status = entry.part?.state?.status ?? "?"
      const inputPreview = JSON.stringify(input).slice(0, 300)
      console.log(`[agent:${run.agentRunId.slice(0, 8)}] [tool_use] ${toolName} (${status}) — ${inputPreview}`)
      return
    }
    if (entry.type === "tool_result") {
      const toolName = entry.part?.tool ?? "?"
      const status = entry.part?.state?.status ?? "?"
      const output = entry.part?.state?.output ?? entry.content ?? ""
      const outputPreview = String(output).slice(0, 200)
      console.log(`[agent:${run.agentRunId.slice(0, 8)}] [tool_result] ${toolName} (${status}) → ${outputPreview}`)
      return
    }
    if (entry.type === "step_finish") {
      const usage = entry.part?.usage ?? entry.usage ?? {}
      console.log(`[agent:${run.agentRunId.slice(0, 8)}] [step_finish] usage=${JSON.stringify(usage)}`)
      return
    }
    // Unknown structured event — log type only
    console.log(`[agent:${run.agentRunId.slice(0, 8)}] [opencode:${entry.type ?? "?"}]`)
  } catch {
    // Non-JSON lines from opencode (startup messages, errors, etc.)
    if (trimmed) console.log(`[agent:${run.agentRunId.slice(0, 8)}] [opencode-raw] ${trimmed.slice(0, 300)}`)
  }
}

function appendLogBuffer(run, field, chunk) {
  const text = chunk.toString("utf8")
  bufferStream(run, field, text)
  if (field === "stderr") {
    const lines = text.split("\n")
    for (const line of lines) {
      const trimmed = line.trim()
      if (trimmed) console.error(`[agent:${run.agentRunId.slice(0, 8)}] [stderr] ${trimmed.slice(0, 500)}`)
    }
    return
  }
  const lines = text.split("\n")
  for (const line of lines) {
    logStdoutLine(run, line)
  }
}

function firstMatch(text, regex) {
  const match = text.match(regex)
  return match?.[1] ?? null
}

function normalizeLogSnippet(value) {
  if (!value) return null
  return String(value)
    .replace(/\\n/g, " ")
    .replace(/\s+/g, " ")
    .trim() || null
}

function detectFailureDiagnostic(run) {
  const stderr = run.stderr ?? ""
  if (!stderr) return null

  const looksLikeLlmFailure = stderr.includes("service=llm") || stderr.includes("AI_APICallError")
  if (!looksLikeLlmFailure) return null

  const provider = normalizeLogSnippet(firstMatch(stderr, /providerID=([^\s]+)/))
  const model = normalizeLogSnippet(firstMatch(stderr, /modelID=([^\s]+)/))
  const statusCode = normalizeLogSnippet(firstMatch(stderr, /"statusCode":(\d+)/))
  const responseBody = normalizeLogSnippet(firstMatch(stderr, /"responseBody":"([^"]*)"/))
  const sessionError = normalizeLogSnippet(firstMatch(stderr, /service=session\.processor error=([^\n]+?) stack=/))

  let detail = "LLM provider request failed"
  if (provider && model) {
    detail = `LLM provider ${provider}/${model} request failed`
  } else if (provider) {
    detail = `LLM provider ${provider} request failed`
  }

  if (statusCode) {
    detail += ` with HTTP ${statusCode}`
  }

  const errorText = responseBody || sessionError
  if (errorText) {
    detail += ` (${errorText})`
  }

  return {
    reasonCode: statusCode === "400" ? "LLM_PROVIDER_BAD_REQUEST" : "LLM_PROVIDER_ERROR",
    error: `${detail}.`,
    summary: `Run ${run.agentRunId}: ${detail} before OpenCode could send a terminal PANDA callback.`
  }
}

async function handleProcessExit(run, code, signal) {
  const state = await readStateFile(run.stateFile)
  if (!state.terminalEventSent) {
    const diagnostic = detectFailureDiagnostic(run)
    const type = run.cancelRequested ? AgentEventType.CANCELLED : AgentEventType.FAILED
    const summary = run.cancelRequested
      ? `Run ${run.agentRunId} was cancelled by the orchestrator.`
      : run.timedOut
        ? `Run ${run.agentRunId} was terminated after exceeding maximum duration (${HTTP.maxRunDurationMinutes} minute(s)).`
        : diagnostic?.summary
          ? diagnostic.summary
        : code === 0
          ? `Run ${run.agentRunId}: OpenCode exited normally but never called a terminal PANDA tool (panda_complete_run, panda_request_input, or panda_fail_run). The agent may have failed to load tools or exhausted its step limit.`
          : `OpenCode exited with code ${code ?? "unknown"}${signal ? ` and signal ${signal}` : ""} without sending a terminal PANDA event.`

    console.log(`[agent:${run.agentRunId.slice(0, 8)}] [exit] code=${code} signal=${signal} timedOut=${run.timedOut} cancel=${run.cancelRequested}`)
    if (run.stderr.trim()) {
      console.error(`[agent:${run.agentRunId.slice(0, 8)}] [stderr-tail]\n${run.stderr.slice(-2000)}`)
    }
    if (run.stdout.trim()) {
      console.log(`[agent:${run.agentRunId.slice(0, 8)}] [stdout-tail]\n${run.stdout.slice(-2000)}`)
    }

    await postEvent({
      eventId: `${run.agentRunId}:${randomUUID()}`,
      workflowId: run.workflowId,
      agentRunId: run.agentRunId,
      type,
      occurredAt: new Date().toISOString(),
      summary,
      reasonCode: diagnostic?.reasonCode,
      details: {
        error: diagnostic?.error,
        exitCode: code,
        signal,
        stdoutTail: run.stdout,
        stderrTail: run.stderr
      }
    })
    await markTerminalState(run.stateFile, type)
  }

  if (activeRun?.agentRunId === run.agentRunId) {
    activeRun = null
  }

  // Save HOME node_modules to cache so subsequent runs skip plugin install
  if (run.homeConfigDir) {
    const homeNodeModules = path.join(run.homeConfigDir, "node_modules")
    const homeNodeModulesCache = path.join(RuntimeConfig.stateRoot, "opencode-home-node-modules-cache")
    try {
      await stat(homeNodeModules)
      await rm(homeNodeModulesCache, { recursive: true, force: true })
      await cp(homeNodeModules, homeNodeModulesCache, { recursive: true })
      console.log("[agent-runtime] Saved HOME node_modules to cache for future runs")
    } catch {
      // node_modules not present — nothing to cache
    }
  }
}

async function startRun(command, response) {
  if (activeRun) {
    json(response, 409, {
      error: "another_run_is_active",
      activeAgentRunId: activeRun.agentRunId
    })
    return
  }

  if (command.type !== AgentCommandType.START_RUN) {
    json(response, 400, { error: "unsupported_command_type" })
    return
  }

  const snap = command.inputSnapshot ?? {}
  console.log(`[agent-runtime] Starting run ${command.agentRunId} phase=${command.phase} ticket=${snap.workItemKey ?? "?"}`)
  console.log(`[agent-runtime] Snapshot keys: ${Object.keys(snap).join(", ")}`)
  if (snap.workspace) {
    console.log(`[agent-runtime] Workspace projectRoot: ${snap.workspace.projectRoot}`)
    const repos = (snap.workspace.repositories ?? []).map(r => `${r.repository}@${r.projectRoot}`)
    console.log(`[agent-runtime] Workspace repos: ${repos.join(", ")}`)
  }
  if (snap.workItem) {
    const wi = snap.workItem
    console.log(`[agent-runtime] WorkItem: key=${wi.key} title=${wi.title} status=${wi.status}`)
    if (wi.description) console.log(`[agent-runtime] Description (first 300): ${String(wi.description).slice(0, 300)}`)
  }
  if (snap.previousRunSummary) {
    console.log(`[agent-runtime] PreviousRunSummary (first 500): ${String(snap.previousRunSummary).slice(0, 500)}`)
  }

  const projectDir = resolveProjectDir(command)
  const stateFile = await createStateFile(command)
  const executionConfig = resolveExecutionConfig(command)
  const { env: childEnv, homeConfigDir } = await prepareOpenCodeEnvironment(projectDir, executionConfig, command.phase)

  const child = spawn(RuntimeConfig.openCodeBinary, buildArgs(command, executionConfig), {
    cwd: projectDir,
    env: {
      ...childEnv,
      [EnvironmentName.pandaOrchestratorUrl]: config.orchestratorUrl,
      [EnvironmentName.pandaWorkflowId]: command.workflowId,
      [EnvironmentName.pandaAgentRunId]: command.agentRunId,
      [EnvironmentName.pandaAgentStateFile]: stateFile,
      [EnvironmentName.pandaAgentPhase]: command.phase,
      [EnvironmentName.pandaAgentObjective]: command.objective
    },
    stdio: ["ignore", "pipe", "pipe"]
  })

  const run = {
    agentRunId: command.agentRunId,
    workflowId: command.workflowId,
    child,
    stateFile,
    homeConfigDir,
    cancelRequested: false,
    timedOut: false,
    maxRunTimer: null,
    stdout: "",
    stderr: ""
  }

  child.stdout.on("data", chunk => appendLogBuffer(run, "stdout", chunk))
  child.stderr.on("data", chunk => appendLogBuffer(run, "stderr", chunk))

  const started = new Promise((resolve, reject) => {
    child.once("spawn", resolve)
    child.once("error", reject)
  })

  try {
    await started
    activeRun = run

    run.maxRunTimer = setTimeout(() => {
      if (activeRun?.agentRunId === run.agentRunId) {
        run.timedOut = true
        run.child.kill(ProcessSignal.SIGTERM)
        setTimeout(() => {
          if (activeRun?.agentRunId === run.agentRunId) {
            run.child.kill(ProcessSignal.SIGKILL)
          }
        }, HTTP.cancelKillDelayMs).unref()
      }
    }, HTTP.maxRunDurationMs)
    run.maxRunTimer.unref()

    child.on("exit", (code, signal) => {
      if (run.maxRunTimer) {
        clearTimeout(run.maxRunTimer)
        run.maxRunTimer = null
      }
      void handleProcessExit(run, code, signal)
    })

    await postEvent({
      eventId: `${command.agentRunId}:${randomUUID()}`,
      workflowId: command.workflowId,
      agentRunId: command.agentRunId,
      type: AgentEventType.RUN_STARTED,
      occurredAt: new Date().toISOString(),
      providerRunRef: `${ProviderRunRef.prefix}${command.agentRunId}`,
      summary: `OpenCode run started for phase ${command.phase}.`
    })

    json(response, 202, {
      status: "accepted",
      agentRunId: command.agentRunId
    })
  } catch (error) {
    child.kill(ProcessSignal.SIGKILL)
    console.error(`[agent-runtime] Unable to start run ${command.agentRunId}:`, error)
    json(response, 502, {
      error: "unable_to_start_run",
      message: error instanceof Error ? error.message : String(error)
    })
  }
}

async function cancelRun(agentRunId, response) {
  if (!activeRun || activeRun.agentRunId !== agentRunId) {
    json(response, 202, {
      status: "ignored",
      agentRunId
    })
    return
  }

  activeRun.cancelRequested = true
  activeRun.child.kill(ProcessSignal.SIGTERM)
  setTimeout(() => {
    if (activeRun?.agentRunId === agentRunId) {
      activeRun.child.kill(ProcessSignal.SIGKILL)
    }
  }, HTTP.cancelKillDelayMs).unref()

  json(response, 202, {
    status: "cancelling",
    agentRunId
  })
}

const server = http.createServer(async (request, response) => {
  try {
    if (request.method === "GET" && request.url === "/health") {
      json(response, 200, {
        status: "ok",
        activeAgentRunId: activeRun?.agentRunId ?? null
      })
      return
    }

    if (request.method === "POST" && request.url === "/internal/agent-runs") {
      const command = await readJson(request)
      await startRun(command, response)
      return
    }

    const cancelMatch = request.method === "POST"
      ? request.url?.match(/^\/internal\/agent-runs\/([0-9a-fA-F-]+)\/cancel$/)
      : null

    if (cancelMatch) {
      await cancelRun(cancelMatch[1], response)
      return
    }

    json(response, 404, { error: "not_found" })
  } catch (error) {
    json(response, 500, {
      error: "internal_error",
      message: error instanceof Error ? error.message : String(error)
    })
  }
})

server.listen(config.port, Host.bindAll)
