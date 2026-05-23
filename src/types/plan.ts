export type Stage = 'intro' | 'planning' | 'confirmed'

export type ColumnId = 'puzzle' | 'merchant' | 'details' | 'map' | 'chat'

export type PlanNode = {
  id: string
  time: string
  title: string
  poiId?: string
  place: string
  lnglat: [number, number]
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
  id: string
  role: 'user' | 'planpal'
  content: string
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
