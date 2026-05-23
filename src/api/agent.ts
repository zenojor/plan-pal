import { agentApi } from '../config/api'
import type { PlanNode } from '../types/plan'

type AgentPlanRequest = {
  prompt: string
  userId: string
}

type AgentPlanStep = {
  action: string
  bookingStatus: string
  note: string
  phase: string
  poiId: string
  poiName: string
  timeRange: string
}

export type AgentPlanResponse = {
  degradationNote: string | null
  notificationText: string
  orderGroupId: string
  planId: string
  status: string
  summary: string
  timeline: AgentPlanStep[]
  userId: string
}

const phaseLabels: Record<string, string> = {
  ACTIVITY: '活动安排',
  DINING: '用餐安排',
  EVENING: '收尾安排',
  TRANSIT: '路程安排',
}

async function parseApiError(response: Response) {
  try {
    const data = (await response.json()) as { message?: string }
    return data.message || `请求失败（${response.status}）`
  } catch {
    return `请求失败（${response.status}）`
  }
}

function extractStartTime(timeRange: string, fallback: string) {
  const [start] = timeRange.split('-')
  return start?.trim() || fallback
}

function buildDetails(step: AgentPlanStep, summary: string, fallback: PlanNode) {
  const parts = [step.note?.trim(), step.bookingStatus?.trim(), summary?.trim()].filter(Boolean)
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

export function mapPlanResponseToNodes(response: AgentPlanResponse, fallbackNodes: PlanNode[]) {
  if (!response.timeline?.length) {
    return fallbackNodes
  }

  return response.timeline.map((step, index) => {
    const fallback = fallbackNodes[index] ?? fallbackNodes[fallbackNodes.length - 1]
    const phaseLabel = phaseLabels[step.phase] ?? '行程安排'
    const bookingStatus = step.bookingStatus?.trim()
    const note = step.note?.trim()

    return {
      id: step.poiId?.trim() || fallback?.id || `step-${index + 1}`,
      time: extractStartTime(step.timeRange, fallback?.time || ''),
      title: step.action?.trim() || fallback?.title || `${phaseLabel} ${index + 1}`,
      place: step.poiName?.trim() || fallback?.place || `待定地点 ${index + 1}`,
      lnglat: fallback?.lnglat ?? [121.4737, 31.2304],
      audience: fallback?.audience || phaseLabel,
      reason: note || `${phaseLabel}：${step.action?.trim() || '待补充'}`,
      budget: fallback?.budget || '待补充',
      status: bookingStatus || phaseLabel,
      details: buildDetails(step, response.summary, fallback),
    }
  })
}
