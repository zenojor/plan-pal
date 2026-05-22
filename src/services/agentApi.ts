import type { AgentRun, AgentStreamEvent, ExecutionAction } from '../types/agent'

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `Request failed with ${response.status}`)
  }
  return response.json() as Promise<T>
}

export async function createAgentRun(input: string): Promise<AgentRun> {
  const response = await fetch('/api/agent/runs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ input }),
  })
  return readJson<AgentRun>(response)
}

export async function executeAgentAction(actionId: string): Promise<ExecutionAction> {
  const response = await fetch(`/api/actions/${encodeURIComponent(actionId)}/execute`, {
    method: 'POST',
  })
  return readJson<ExecutionAction>(response)
}

export async function streamAgentRun(
  input: string,
  onEvent: (event: AgentStreamEvent) => void,
): Promise<void> {
  const response = await fetch('/api/agent/runs/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ input }),
  })
  if (!response.ok || !response.body) {
    const text = await response.text()
    throw new Error(text || `Request failed with ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const chunks = buffer.split('\n\n')
    buffer = chunks.pop() ?? ''
    for (const chunk of chunks) {
      const event = parseSseChunk(chunk)
      if (event) onEvent(event)
    }
  }

  if (buffer.trim()) {
    const event = parseSseChunk(buffer)
    if (event) onEvent(event)
  }
}

function parseSseChunk(chunk: string): AgentStreamEvent | null {
  const eventLine = chunk.split('\n').find((line) => line.startsWith('event: '))
  const dataLine = chunk.split('\n').find((line) => line.startsWith('data: '))
  if (!eventLine || !dataLine) return null

  return {
    event: eventLine.slice(7),
    data: JSON.parse(dataLine.slice(6)),
  } as AgentStreamEvent
}
