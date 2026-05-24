export type Stage = 'intro' | 'planning' | 'confirmed'

export type ColumnId = 'puzzle' | 'merchant' | 'details' | 'map' | 'chat' | 'dev'

export type PlanNode = {
  id: string
  segmentId?: string
  time: string
  title: string
  poiId?: string
  source?: string
  place: string
  lnglat: [number, number]
  address?: string
  telephone?: string
  businessHours?: string
  typeCode?: string
  audience: string
  reason: string
  budget: string
  status: string
  details: string
  startTime?: string
  endTime?: string
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

export type ChatMessage = {
  actionCard?: {
    id: string
    title: string
    description: string
    options: Array<{
      id: string
      label: string
      description: string
      actionType: 'SUBMIT_PATCH' | 'OPEN_REWRITE' | 'OPEN_REPLACE' | 'REORDER_HINT' | 'ROLLBACK_VERSION' | 'BUILD_PLAN'
      targetSegmentId?: string | null
      prompt?: string | null
      planPatch?: unknown | null
      poiIds?: string[] | null
    }>
    inputPlaceholder?: string | null
    allowCustomInput: boolean
  } | null
  id: string
  planPatch?: unknown | null
  intent?: any | null
  role: 'user' | 'planpal'
  content: string
  isLoading?: boolean
}

export type MerchantProfile = {
  address: string
  queue: string
  booking: string
  hours: string
  contact: string
  tags: string[]
}

export type RouteSegmentInfo = {
  walking: { duration: number; distance: number } | null
  transit: { duration: number; transfers: number } | null
  driving: { duration: number; distance: number } | null
}
