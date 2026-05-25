// Type declarations for AMap JS API 2.0
// Reference: https://lbs.amap.com/api/javascript-api-v2/documentation

declare namespace AMap {
  interface MapOptions {
    zoom?: number
    center?: [number, number]
    mapStyle?: string
    viewMode?: '2D' | '3D'
    features?: string[]
    pitch?: number
    [key: string]: unknown
  }

  interface MarkerOptions {
    position?: [number, number]
    map?: Map
    content?: string | HTMLElement
    offset?: Pixel
    anchor?: string
    zIndex?: number
    [key: string]: unknown
  }

  interface PolylineOptions {
    path?: [number, number][]
    strokeColor?: string
    strokeWeight?: number
    strokeStyle?: 'solid' | 'dashed'
    strokeOpacity?: number
    lineJoin?: 'miter' | 'round' | 'bevel'
    lineCap?: 'butt' | 'round' | 'square'
    strokeDasharray?: number[]
    map?: Map
    [key: string]: unknown
  }

  interface CircleMarkerOptions {
    center?: [number, number]
    radius?: number
    strokeColor?: string
    strokeWeight?: number
    strokeOpacity?: number
    fillColor?: string
    fillOpacity?: number
    map?: Map
    [key: string]: unknown
  }

  class Pixel {
    constructor(x: number, y: number)
  }

  class LngLat {
    constructor(lng: number, lat: number)
    getLng(): number
    getLat(): number
  }

  class Map {
    constructor(container: string | HTMLElement, opts?: MapOptions)
    setFitView(overlays?: unknown[], immediately?: boolean, avoid?: number[], maxZoom?: number): void
    add(overlay: unknown | unknown[]): void
    remove(overlay: unknown | unknown[]): void
    clearMap(): void
    destroy(): void
    setCenter(center: [number, number]): void
    setZoom(zoom: number): void
    on(event: string, handler: (...args: unknown[]) => void): void
  }

  class Marker {
    constructor(opts?: MarkerOptions)
    setMap(map: Map | null): void
    setPosition(position: [number, number]): void
  }

  class Polyline {
    constructor(opts?: PolylineOptions)
    setMap(map: Map | null): void
  }

  class CircleMarker {
    constructor(opts?: CircleMarkerOptions)
    setMap(map: Map | null): void
  }

  // Route planning plugins
  interface RouteResult {
    info: string
    routes?: RouteDetail[]
    plans?: TransitPlan[]
  }

  interface RouteDetail {
    distance: number // meters
    time: number // seconds
  }

  interface TransitPlan {
    distance: number
    time: number
    segments: TransitSegment[]
  }

  interface TransitSegment {
    transit_mode: string
    bus?: { buslines: { name: string }[] }
  }

  type RouteCallback = (status: 'complete' | 'error' | 'no_data', result: RouteResult) => void

  interface RoutePlannerOptions {
    map?: Map
    panel?: string | HTMLElement
    policy?: number
    autoFitView?: boolean
    hideMarkers?: boolean
    isOutline?: boolean
    outlineColor?: string
    [key: string]: unknown
  }

  class Walking {
    constructor(opts?: RoutePlannerOptions)
    search(
      origin: [number, number],
      destination: [number, number],
      callback: RouteCallback,
    ): void
  }

  class Transfer {
    constructor(opts?: RoutePlannerOptions & { city?: string })
    search(
      origin: [number, number],
      destination: [number, number],
      callback: RouteCallback,
    ): void
  }

  class Driving {
    constructor(opts?: RoutePlannerOptions)
    search(
      origin: [number, number],
      destination: [number, number],
      callback: RouteCallback,
    ): void
  }
}

interface Window {
  _AMapSecurityConfig?: {
    securityJsCode: string
  }
  AMap?: typeof AMap
}
