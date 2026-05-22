import type { MerchantProfile, PlanNode } from './plan'

export type RunStatus =
  | 'thinking'
  | 'planning'
  | 'ready'
  | 'executing'
  | 'done'
  | 'failed'
  | 'needs_more_info'

export type PlanningRequest = {
  raw_input: string
  time_range: string
  party_size: number
  scene: 'family' | 'friends'
  child_age: number | null
  preferences: string[]
  constraints: string[]
  missing_fields: string[]
}

export type RouteSegment = {
  from_place: string
  to_place: string
  walking_minutes: number
  transit_minutes: number
  driving_minutes: number
  distance_km: number
  summary: string
}

export type ToolCallRecord = {
  tool_name: string
  input_summary: string
  output_summary: string
  status: 'success' | 'failed' | 'degraded'
  duration_ms: number
}

export type ExecutionAction = {
  id: string
  run_id: string
  type: 'reserve_restaurant' | 'book_activity' | 'queue' | 'send_plan'
  target: string
  label: string
  confirm_text: string
  status: 'pending' | 'completed' | 'failed'
  failure_reason: string | null
}

export type AgentRun = {
  run_id: string
  status: RunStatus
  input: string
  request: PlanningRequest | null
  plan_nodes: PlanNode[]
  merchant_profiles: Record<string, MerchantProfile>
  route_segments: RouteSegment[]
  tool_calls: ToolCallRecord[]
  execution_actions: ExecutionAction[]
  message: string
}

export type AgentStreamEvent =
  | { event: 'run_started'; data: AgentRun }
  | { event: 'tool_call'; data: ToolCallRecord }
  | { event: 'plan_node'; data: PlanNode }
  | { event: 'route_segments'; data: { segments: RouteSegment[] } }
  | { event: 'execution_actions'; data: { actions: ExecutionAction[] } }
  | { event: 'completed'; data: AgentRun }
  | { event: 'error'; data: { message: string } }
