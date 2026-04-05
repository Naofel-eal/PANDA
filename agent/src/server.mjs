import { randomUUID } from "node:crypto"
import { spawn } from "node:child_process"
import { copyFile, cp, mkdir, readFile, rm, writeFile } from "node:fs/promises"
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
  orchestratorUrl: process.env[EnvironmentName.devflowOrchestratorUrl]?.trim() ?? RuntimeConfig.orchestratorUrl
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
  return [
    "Devflow run",
    `workflowId: ${command.workflowId}`,
    `agentRunId: ${command.agentRunId}`,
    `phase: ${command.phase}`,
    `objective: ${command.objective}`,
    "",
    "Input snapshot JSON:",
    JSON.stringify(command.inputSnapshot ?? {}, null, 2),
    "",
    "Follow the Devflow agent protocol strictly.",
    "Use the Devflow tools for progress, blocker, completion, or failure.",
    "Do not try to interact with external systems directly."
  ].join("\n")
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

async function prepareOpenCodeEnvironment(projectDir, executionConfig) {
  const env = { ...process.env }
  const runtimeId = randomUUID()
  const runtimeRoot = path.join(RuntimeConfig.stateRoot, "opencode-runtime", runtimeId)
  const runtimeHome = path.join(runtimeRoot, "home")

  await mkdir(runtimeHome, { recursive: true })
  env.HOME = runtimeHome
  applyExecutionConfig(env, executionConfig)
  await materializeWorkspaceProject(projectDir, executionConfig)
  return env
}

async function materializeWorkspaceProject(projectDir, executionConfig) {
  const workspaceConfigDir = path.join(projectDir, ".opencode")
  const workspaceConfigFile = path.join(projectDir, "opencode.json")
  const workspaceAgentsFile = path.join(projectDir, "AGENTS.md")

  await mkdir(projectDir, { recursive: true })
  await rm(workspaceAgentsFile, { force: true })
  await rm(workspaceConfigFile, { force: true })
  await rm(workspaceConfigDir, { recursive: true, force: true })

  await copyFile(path.join(RuntimeConfig.templateDir, "AGENTS.md"), workspaceAgentsFile)
  await cp(path.join(RuntimeConfig.templateDir, ".opencode"), workspaceConfigDir, { recursive: true })
  await copyFile(path.join(RuntimeConfig.templateDir, "opencode.json"), workspaceConfigFile)
  await applyWorkspaceModelConfig(workspaceConfigFile, executionConfig)
}

async function applyWorkspaceModelConfig(workspaceConfigFile, executionConfig) {
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

function appendLogBuffer(run, field, chunk) {
  const text = chunk.toString("utf8")
  const streamLabel = field === "stdout" ? "stdout" : "stderr"
  console.log(`[agent-runtime] ${streamLabel} ${run.agentRunId}: ${text.trimEnd()}`)
  const value = `${run[field]}${text}`
  run[field] = value.length > HTTP.logTailMaxChars ? value.slice(-HTTP.logTailMaxChars) : value
}

async function handleProcessExit(run, code, signal) {
  console.log(
    `[agent-runtime] Process exit for run ${run.agentRunId} (workflow ${run.workflowId}) with code=${code ?? "null"} signal=${signal ?? "null"}`
  )
  const state = await readStateFile(run.stateFile)
  if (!state.terminalEventSent) {
    const type = run.cancelRequested ? AgentEventType.CANCELLED : AgentEventType.FAILED
    const summary = run.cancelRequested
      ? `Run ${run.agentRunId} was cancelled by the orchestrator.`
      : code === 0
        ? "OpenCode exited without sending a terminal Devflow event."
        : `OpenCode exited with code ${code ?? "unknown"}${signal ? ` and signal ${signal}` : ""}.`

    await postEvent({
      eventId: `${run.agentRunId}:${randomUUID()}`,
      workflowId: run.workflowId,
      agentRunId: run.agentRunId,
      type,
      occurredAt: new Date().toISOString(),
      summary,
      details: {
        exitCode: code,
        signal,
        stdoutTail: run.stdout,
        stderrTail: run.stderr
      }
    })
    await markTerminalState(run.stateFile, type)
    console.log(`[agent-runtime] Sent fallback terminal event ${type} for run ${run.agentRunId}`)
  }

  if (activeRun?.agentRunId === run.agentRunId) {
    activeRun = null
    console.log(`[agent-runtime] Cleared active run ${run.agentRunId}`)
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

  const projectDir = resolveProjectDir(command)
  const stateFile = await createStateFile(command)
  const executionConfig = resolveExecutionConfig(command)
  const childEnv = await prepareOpenCodeEnvironment(projectDir, executionConfig)
  console.log(
    `[agent-runtime] Starting run ${command.agentRunId} for workflow ${command.workflowId} in phase ${command.phase} from ${projectDir}`
  )

  const child = spawn(RuntimeConfig.openCodeBinary, buildArgs(command, executionConfig), {
    cwd: projectDir,
    env: {
      ...childEnv,
      [EnvironmentName.devflowOrchestratorUrl]: config.orchestratorUrl,
      [EnvironmentName.devflowWorkflowId]: command.workflowId,
      [EnvironmentName.devflowAgentRunId]: command.agentRunId,
      [EnvironmentName.devflowAgentStateFile]: stateFile,
      [EnvironmentName.devflowAgentPhase]: command.phase,
      [EnvironmentName.devflowAgentObjective]: command.objective
    },
    stdio: ["ignore", "pipe", "pipe"]
  })

  const run = {
    agentRunId: command.agentRunId,
    workflowId: command.workflowId,
    child,
    stateFile,
    cancelRequested: false,
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
    console.log(`[agent-runtime] Spawned OpenCode process for run ${command.agentRunId}`)
    child.on("exit", (code, signal) => {
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
    console.log(`[agent-runtime] Sent RUN_STARTED for run ${command.agentRunId}`)

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
  console.log(`[agent-runtime] Cancelling run ${agentRunId} with ${ProcessSignal.SIGTERM}`)
  activeRun.child.kill(ProcessSignal.SIGTERM)
  setTimeout(() => {
    if (activeRun?.agentRunId === agentRunId) {
      console.log(`[agent-runtime] Forcing kill for run ${agentRunId} with ${ProcessSignal.SIGKILL}`)
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

server.listen(config.port, Host.bindAll, () => {
  console.log(`Devflow agent runtime listening on ${Host.bindAll}:${config.port}`)
})
