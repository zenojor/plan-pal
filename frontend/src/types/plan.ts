export type Stage = 'intro' | 'planning' | 'confirmed'

export type ColumnId = 'puzzle' | 'merchant' | 'details' | 'map' | 'chat' | 'dev'

export type RouteMode = 'WALK' | 'PUBLIC_TRANSIT' | 'TAXI'

export type SelectedRouteChoice = {
  segmentKey: string
  mode: RouteMode
  fromNodeId: string
  toNodeId: string
  fromPoiName: string
  toPoiName: string
  duration: number
  distance: number
  priceEstimate?: string
  transfers?: number
}

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
  activity?: Array<{
    id: string
    type: string
    label: string
    detail?: string
    status: 'running' | 'done' | 'error'
  }>
  actionCard?: {
    id: string
    title: string
    description: string
    options: Array<{
      id: string
      label: string
      description: string
      actionType: 'SUBMIT_PATCH' | 'OPEN_REWRITE' | 'OPEN_REPLACE' | 'REORDER_HINT' | 'ROLLBACK_VERSION' | 'BUILD_PLAN' | 'SELECT_PREFERENCE' | 'REQUEST_POI_RESEARCH'
      targetSegmentId?: string | null
      prompt?: string | null
      planPatch?: unknown | null
      poiIds?: string[] | null
      optionKind?: 'PREFERENCE' | 'POI' | 'MOVIE_SCREENING' | 'PLAN_CHOICE' | string | null
      poiPreview?: {
        poiId: string
        name: string
        category: string
        distanceKm: number
        tags: string[]
        address: string
        businessHours: string
        telephone: string
        source: string
        placeholderImage: string
      } | null
    }>
    inputPlaceholder?: string | null
    allowCustomInput: boolean
    cardKind?: 'PREFERENCE' | 'POI' | 'MOVIE_SCREENING' | 'PLAN_CHOICE' | string | null
  } | null
  id: string
  planPatch?: unknown | null
  intent?: any | null
  role: 'user' | 'planpal'
  content: string
  isLoading?: boolean
}

export type MerchantProduct = {
  id: string
  name: string
  description: string
  price: number
  originalPrice?: number
  tags: string[]
  sold?: string
  rating?: string
  thumbnail?: string
}

export type MerchantProfile = {
  address: string
  queue: string
  booking: string
  hours: string
  contact: string
  tags: string[]
  thumbnail?: string
  rating?: string
  avgPrice?: string
  products?: MerchantProduct[]
}

export type RouteSegmentInfo = {
  walking: { duration: number; distance: number } | null
  transit: { duration: number; transfers: number } | null
  driving: { duration: number; distance: number } | null
}
