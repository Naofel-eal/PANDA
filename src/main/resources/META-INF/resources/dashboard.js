const state = {
  tickets: [],
  query: ""
}

const DashboardEndpoint = Object.freeze({
  tickets: "/api/v1/dashboard/tickets"
})

const HistoryTitles = new Set([
  "Ticket transition requested",
  "Ticket status changed",
  "Ticket comment requested",
  "New ticket comment",
  "Updated ticket comment",
  "New code review comment",
  "Updated code review comment",
  "Workflow blocked for input"
])

const ticketsRoot = document.querySelector("#tickets")
const searchInput = document.querySelector("#search-input")
const refreshButton = document.querySelector("#refresh-button")
const lastRefresh = document.querySelector("#last-refresh")
const ticketTemplate = document.querySelector("#ticket-template")
const historyEntryTemplate = document.querySelector("#history-entry-template")

function dashboardReady() {
  return Boolean(
    ticketsRoot
    && searchInput
    && refreshButton
    && lastRefresh
    && ticketTemplate
    && historyEntryTemplate
  )
}

function formatDate(value) {
  if (!value) {
    return "-"
  }
  return new Intl.DateTimeFormat("en-GB", {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value))
}

function titleCase(value) {
  return String(value ?? "")
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map(part => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ")
}

function createEmptyState(message) {
  const node = document.createElement("div")
  node.className = "empty-state"
  node.textContent = message
  return node
}

function isHistoryEntry(entry) {
  return HistoryTitles.has(entry.title)
}

function normalizeSummary(summary) {
  if (!summary) {
    return ""
  }
  if (/^[A-Z0-9_]+$/.test(summary)) {
    return titleCase(summary)
  }
  return summary
}

function historyForTicket(ticket) {
  return [...(ticket.timeline ?? [])]
    .filter(isHistoryEntry)
    .sort((left, right) => new Date(right.occurredAt) - new Date(left.occurredAt))
}

function latestTicketActivity(ticket) {
  const history = historyForTicket(ticket)
  return history[0]?.occurredAt ?? ticket.updatedAt ?? ticket.createdAt ?? ""
}

function appendTextWithLinks(root, text) {
  const value = normalizeSummary(text)
  const urlPattern = /(https?:\/\/[^\s]+)/g
  let lastIndex = 0
  let match

  while ((match = urlPattern.exec(value)) !== null) {
    if (match.index > lastIndex) {
      root.append(document.createTextNode(value.slice(lastIndex, match.index)))
    }
    const link = document.createElement("a")
    link.href = match[0]
    link.target = "_blank"
    link.rel = "noreferrer"
    link.textContent = match[0]
    root.append(link)
    lastIndex = match.index + match[0].length
  }

  if (lastIndex < value.length) {
    root.append(document.createTextNode(value.slice(lastIndex)))
  }
}

function renderHistoryEntry(entry) {
  if (!historyEntryTemplate) {
    return createEmptyState(entry.summary || entry.title)
  }
  const fragment = historyEntryTemplate.content.cloneNode(true)
  const title = fragment.querySelector(".history-title")
  const date = fragment.querySelector(".history-date")
  const summary = fragment.querySelector(".history-summary")

  title.textContent = entry.title
  date.textContent = formatDate(entry.occurredAt)
  appendTextWithLinks(summary, entry.summary || entry.title)

  return fragment
}

function renderTicket(ticket) {
  if (!ticketTemplate) {
    return createEmptyState(`${ticket.workItemKey}: template unavailable`)
  }
  const history = historyForTicket(ticket)
  const fragment = ticketTemplate.content.cloneNode(true)
  const key = fragment.querySelector(".ticket-key")
  const link = fragment.querySelector(".ticket-link")
  const title = fragment.querySelector(".ticket-title")
  const phase = fragment.querySelector(".ticket-phase")
  const status = fragment.querySelector(".ticket-status")
  const updated = fragment.querySelector(".ticket-updated")
  const historyRoot = fragment.querySelector(".history-list")

  key.textContent = `${ticket.workItemSystem}:${ticket.workItemKey}`
  title.textContent = ticket.title || ticket.workItemKey
  phase.textContent = titleCase(ticket.phase)
  status.textContent = titleCase(ticket.status)
  status.classList.add(`status-${String(ticket.status).toLowerCase()}`)
  updated.textContent = `Last activity: ${formatDate(latestTicketActivity(ticket))}`

  if (ticket.url) {
    link.href = ticket.url
  } else {
    link.removeAttribute("href")
    link.textContent = "No link"
  }

  historyRoot.replaceChildren(...(history.length
    ? history.map(renderHistoryEntry)
    : [createEmptyState("No comments or state transitions yet.")]))

  return fragment
}

function filterTickets(tickets) {
  const query = state.query.trim().toLowerCase()
  const sorted = [...tickets].sort((left, right) => new Date(latestTicketActivity(right)) - new Date(latestTicketActivity(left)))

  if (!query) {
    return sorted
  }

  return sorted.filter(ticket => {
    const historyText = historyForTicket(ticket)
      .flatMap(entry => [entry.title, entry.summary, entry.category])
      .join(" ")

    const haystack = [
      ticket.workItemKey,
      ticket.workItemSystem,
      ticket.title,
      ticket.status,
      ticket.phase,
      historyText
    ]
      .filter(Boolean)
      .join(" ")
      .toLowerCase()

    return haystack.includes(query)
  })
}

function render() {
  if (!ticketsRoot) {
    return
  }
  const tickets = filterTickets(state.tickets)
  ticketsRoot.replaceChildren(...(tickets.length
    ? tickets.map(renderTicket)
    : [createEmptyState("No tickets match the current filter.")]))
}

async function loadTickets() {
  if (!dashboardReady()) {
    console.error("Dashboard DOM is not ready for ticket history rendering.")
    return
  }
  refreshButton.disabled = true
  try {
    const response = await fetch(DashboardEndpoint.tickets, { headers: { accept: "application/json" } })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    state.tickets = await response.json()
    lastRefresh.textContent = formatDate(new Date().toISOString())
    render()
  } catch (error) {
    ticketsRoot.replaceChildren(createEmptyState(`Unable to load ticket history: ${error}`))
  } finally {
    refreshButton.disabled = false
  }
}

if (searchInput) {
  searchInput.addEventListener("input", event => {
    state.query = event.target.value
    render()
  })
}

if (refreshButton) {
  refreshButton.addEventListener("click", () => {
    void loadTickets()
  })
}

if (dashboardReady()) {
  void loadTickets()
  setInterval(() => {
    void loadTickets()
  }, 10000)
} else {
  console.error("Dashboard assets are out of sync with the current page markup.")
}
