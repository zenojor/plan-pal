import { agentApi } from '../config/api'
import type { PlanNode } from '../types/plan'

export type AgentPlanRequest = {
  prompt: string
  userId: string
}

export type AgentPlanIntent = {
  headcount: number
  participants: string[]
  startTime: string
  endTime: string
  totalMinutes: number
  sceneType: string
  requestedSegments: string[]
  dietaryConstraints: string[]
  drinkPreference: string
  locationScope: string
  originalPrompt: string
  isConsultingMode?: boolean
}

export type AgentOrderIntent = {
  orderIntentId: string
  type: string
  poiId: string
  poiName: string
  headcount: number
  targetTime: string
  status: string
}

export type AgentPlanStep = {
  action: string
  bookingStatus: string
  note: string
  phase: string
  poiId: string
  poiName: string
  durationMinutes: number
  startTime?: string
  endTime?: string
  lnglat?: number[] | null
  audience: string
  reason: string
  budget: string
  headcount?: number
  constraints?: string
  executionStatus?: string
  orderIntentId?: string
  isTransit?: boolean
  transportMode?: string
  distanceKm?: number
  fromPoiName?: string
  toPoiName?: string
}

export type AgentPlanResponse = {
  degradationNote: string | null
  executionStatus?: string
  intent?: AgentPlanIntent | null
  notificationText: string
  orderGroupId: string
  orderIntents?: AgentOrderIntent[]
  planId: string
  status: string
  summary: string
  timeline: AgentPlanStep[]
  userId: string
}

export type AgentPlanStreamEvent = {
  content: string
  degradationNote?: string | null
  executionStatus?: string | null
  intent?: AgentPlanIntent | null
  notificationText?: string | null
  orderGroupId?: string | null
  orderIntents?: AgentOrderIntent[] | null
  planId?: string | null
  status?: string | null
  step: number
  timeline?: AgentPlanStep[] | null
  type: string
}

export type ConfirmPlanRequest = {
  planId: string
  userId: string
  timeline: AgentPlanStep[]
  headcount: number
  notificationText: string
}

export type ConfirmPlanResponse = {
  orderGroupId: string
  status: string
  executedOrders: string[]
  failedOrders: string[]
  notificationText: string
  timeline: AgentPlanStep[]
}

type PlanStreamHandlers = {
  onError: (error: Error) => void
  onEvent?: (event: AgentPlanStreamEvent) => void
  onFinish: (response: AgentPlanResponse) => void
  onTimeline?: (response: AgentPlanResponse, event: AgentPlanStreamEvent) => void
}

const phaseLabels: Record<string, string> = {
  ACTIVITY: '活动安排',
  DINING: '用餐安排',
  DRINKS: '小酌安排',
  EVENING: '收尾安排',
  LEISURE: '休闲安排',
  TRANSIT: '路程安排',
}

function toLngLatTuple(value?: number[] | null): [number, number] | null {
  if (!value || value.length < 2) {
    return null
  }

  return [value[0], value[1]]
}

async function parseApiError(response: Response) {
  try {
    const data = (await response.json()) as { message?: string }
    return data.message || `请求失败（${response.status}）`
  } catch {
    return `请求失败（${response.status}）`
  }
}

function formatTime(step: AgentPlanStep, fallback: string) {
  if (step.startTime && step.endTime) {
    return `${step.startTime}-${step.endTime}`
  }
  return step.durationMinutes ? `${step.durationMinutes}分钟` : fallback
}

function formatStatus(step: AgentPlanStep, phaseLabel: string) {
  if (step.isTransit) return step.transportMode || '交通'
  const status = step.bookingStatus?.trim()
  if (step.executionStatus === 'EXECUTED') return status || '已下单'
  if (step.executionStatus === 'FAILED') return status || '执行失败'
  if (step.executionStatus === 'PENDING_CONFIRMATION') return status || '待确认'
  return status || phaseLabel
}

function buildDetails(step: AgentPlanStep, summary: string, fallback: PlanNode) {
  const headcount = step.headcount ? `${step.headcount}人` : ''
  const constraints = step.constraints ? `约束：${step.constraints}` : ''
  const parts = [
    step.note?.trim(),
    step.bookingStatus?.trim(),
    headcount,
    constraints,
    summary?.trim(),
  ].filter(Boolean)
  return parts.join(' · ') || fallback.details
}

export async function requestPlan(payload: AgentPlanRequest) {
  const response = await fetch(agentApi.plan, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  })

  if (!response.ok) {
    throw new Error(await parseApiError(response))
  }

  return (await response.json()) as AgentPlanResponse
}

export async function confirmPlan(planId: string, payload: ConfirmPlanRequest) {
  const response = await fetch(agentApi.confirmPlan(planId), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  })

  if (!response.ok) {
    throw new Error(await parseApiError(response))
  }

  return (await response.json()) as ConfirmPlanResponse
}

export function requestPlanStream(payload: AgentPlanRequest, handlers: PlanStreamHandlers) {
  const params = new URLSearchParams({
    userId: payload.userId,
    prompt: payload.prompt,
  })
  const source = new EventSource(`${agentApi.planStream}?${params.toString()}`)
  let completed = false

  function toResponse(event: AgentPlanStreamEvent): AgentPlanResponse {
    return {
      degradationNote: event.degradationNote ?? null,
      executionStatus: event.executionStatus || 'PENDING_CONFIRMATION',
      intent: event.intent ?? null,
      notificationText: event.notificationText || event.content,
      orderGroupId: event.orderGroupId || '',
      orderIntents: event.orderIntents ?? [],
      planId: event.planId || '',
      status: event.status || 'SUCCESS',
      summary: event.content,
      timeline: event.timeline ?? [],
      userId: payload.userId,
    }
  }

  function handleEvent(message: MessageEvent<string>) {
    try {
      const event = JSON.parse(message.data) as AgentPlanStreamEvent
      handlers.onEvent?.(event)

      if (event.timeline?.length) {
        handlers.onTimeline?.(toResponse(event), event)
      }

      if (event.type === 'FINISH') {
        completed = true
        handlers.onFinish(toResponse(event))
        source.close()
      }

      if (event.type === 'ERROR') {
        completed = true
        handlers.onError(new Error(event.content || '规划流式请求失败'))
        source.close()
      }
    } catch {
      completed = true
      handlers.onError(new Error('规划流式事件解析失败'))
      source.close()
    }
  }

  for (const eventName of [
    'START',
    'INTENT',
    'THOUGHT',
    'ACTION',
    'OBSERVATION',
    'PLAN_STEP',
    'FINISH',
    'ERROR',
  ]) {
    source.addEventListener(eventName, handleEvent)
  }

  source.onerror = () => {
    if (completed) return
    completed = true
    handlers.onError(new Error('规划流式连接中断'))
    source.close()
  }

  return () => {
    completed = true
    source.close()
  }
}

export function requestPlanChatStream(
  planId: string,
  payload: AgentPlanRequest,
  handlers: PlanStreamHandlers
) {
  const params = new URLSearchParams({
    userId: payload.userId,
    prompt: payload.prompt,
  })
  const source = new EventSource(`${agentApi.planChatStream(planId)}?${params.toString()}`)
  let completed = false

  function toResponse(event: AgentPlanStreamEvent): AgentPlanResponse {
    return {
      degradationNote: event.degradationNote ?? null,
      executionStatus: event.executionStatus || 'PENDING_CONFIRMATION',
      intent: event.intent ?? null,
      notificationText: event.notificationText || event.content,
      orderGroupId: event.orderGroupId || '',
      orderIntents: event.orderIntents ?? [],
      planId: event.planId || '',
      status: event.status || 'SUCCESS',
      summary: event.content,
      timeline: event.timeline ?? [],
      userId: payload.userId,
    }
  }

  function handleEvent(message: MessageEvent<string>) {
    try {
      const event = JSON.parse(message.data) as AgentPlanStreamEvent
      handlers.onEvent?.(event)

      if (event.timeline?.length) {
        handlers.onTimeline?.(toResponse(event), event)
      }

      if (event.type === 'FINISH') {
        completed = true
        handlers.onFinish(toResponse(event))
        source.close()
      }

      if (event.type === 'ERROR') {
        completed = true
        handlers.onError(new Error(event.content || '规划流式请求失败'))
        source.close()
      }
    } catch {
      completed = true
      handlers.onError(new Error('规划流式事件解析失败'))
      source.close()
    }
  }

  for (const eventName of [
    'START',
    'INTENT',
    'THOUGHT',
    'ACTION',
    'OBSERVATION',
    'PLAN_STEP',
    'FINISH',
    'ERROR',
  ]) {
    source.addEventListener(eventName, handleEvent)
  }

  source.onerror = () => {
    if (completed) return
    completed = true
    handlers.onError(new Error('规划流式连接中断'))
    source.close()
  }

  return () => {
    completed = true
    source.close()
  }
}

export function mapPlanResponseToNodes(response: AgentPlanResponse, fallbackNodes: PlanNode[]) {
  if (!response.timeline?.length) {
    return fallbackNodes
  }

  return response.timeline.map((step, index) => {
    const fallback = fallbackNodes[index] ?? fallbackNodes[fallbackNodes.length - 1]
    const phaseLabel = phaseLabels[step.phase] ?? '行程安排'
    const note = step.note?.trim()
    const id = step.orderIntentId?.trim() || step.poiId?.trim() || fallback?.id || `step-${index + 1}`
    const isTransit = step.isTransit || step.phase === 'TRANSIT'

    return {
      id,
      time: formatTime(step, fallback?.time || ''),
      title: step.action?.trim() || fallback?.title || `${phaseLabel} ${index + 1}`,
      poiId: step.poiId,
      place: step.poiName?.trim() || fallback?.place || `待定地点 ${index + 1}`,
      lnglat: toLngLatTuple(step.lnglat) ?? fallback?.lnglat ?? [121.4737, 31.2304],
      audience: step.audience || fallback?.audience || phaseLabel,
      reason: step.reason || note || `${phaseLabel}：${step.action?.trim() || '待补充'}`,
      budget: step.budget || fallback?.budget || '待补充',
      status: formatStatus(step, phaseLabel),
      details: buildDetails(step, response.summary, fallback),
      startTime: step.startTime,
      endTime: step.endTime,
      headcount: step.headcount,
      constraints: step.constraints,
      executionStatus: step.executionStatus,
      orderIntentId: step.orderIntentId,
      isTransit,
      transportMode: step.transportMode,
      distanceKm: step.distanceKm,
      fromPoiName: step.fromPoiName,
      toPoiName: step.toPoiName,
    }
  })
}
