import { Card } from 'animal-island-ui'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { PlanNode, RouteMode, RouteSegmentInfo, SelectedRouteChoice } from '../types/plan'

type MapColumnProps = {
  nodes: PlanNode[]
  selectedRouteChoices: Record<string, SelectedRouteChoice>
  onRouteChoiceChange: (segmentKey: string, choice: SelectedRouteChoice) => void
}

type RouteOption = {
  mode: RouteMode
  label: string
  detail: string
  duration: number
  distance: number
  priceEstimate?: string
  transfers?: number
}

const routeStyles: Record<RouteMode, { bg: string; border: string; text: string; line: string; shadow: string }> = {
  WALK: { bg: '#e6f9f6', border: '#82d5bb', text: '#0f4c46', line: '#19c8b9', shadow: '#82d5bb' },
  PUBLIC_TRANSIT: { bg: '#e8ecfd', border: '#889df0', text: '#4e5fa8', line: '#889df0', shadow: '#b7c6e5' },
  TAXI: { bg: '#fff3c4', border: '#f7cd67', text: '#794f27', line: '#f5c31c', shadow: '#dba90e' },
}

function segmentKey(from: PlanNode, to: PlanNode) {
  return `${from.id}->${to.id}`
}

function taxiPrice(distance: number) {
  const min = Math.max(14, Math.round(14 + Math.max(0, distance - 3) * 3.2))
  const max = min + Math.max(4, Math.round(distance * 1.5))
  return `CNY ${min}-${max}`
}

function fallbackDistance(from: [number, number], to: [number, number]) {
  const rad = Math.PI / 180
  const earthKm = 6371
  const dLat = (to[1] - from[1]) * rad
  const dLng = (to[0] - from[0]) * rad
  const lat1 = from[1] * rad
  const lat2 = to[1] * rad
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2
  return 2 * earthKm * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

function optionsForSegment(from: PlanNode, to: PlanNode, segment?: RouteSegmentInfo): RouteOption[] {
  const roughDistance = fallbackDistance(from.lnglat, to.lnglat)
  const walkingDistance = segment?.walking?.distance ?? roughDistance
  const taxiDistance = segment?.driving?.distance ?? roughDistance
  const transitDistance = segment?.driving?.distance ?? walkingDistance

  return [
    {
      mode: 'WALK',
      label: '步行',
      detail: '不产生订单，适合短距离慢走',
      duration: segment?.walking?.duration ?? Math.max(6, Math.round((walkingDistance / 4.5) * 60)),
      distance: walkingDistance,
    },
    {
      mode: 'PUBLIC_TRANSIT',
      label: '公交/地铁',
      detail: `换乘 ${segment?.transit?.transfers ?? 0} 次`,
      duration: segment?.transit?.duration ?? Math.max(12, Math.round((transitDistance / 22) * 60) + 8),
      distance: transitDistance,
      transfers: segment?.transit?.transfers ?? 0,
    },
    {
      mode: 'TAXI',
      label: '打车',
      detail: '确认方案时模拟网约车订单',
      duration: segment?.driving?.duration ?? Math.max(8, Math.round((taxiDistance / 28) * 60) + 5),
      distance: taxiDistance,
      priceEstimate: taxiPrice(taxiDistance),
    },
  ]
}

function defaultMode(options: RouteOption[]) {
  const walking = options.find((option) => option.mode === 'WALK')
  const transit = options.find((option) => option.mode === 'PUBLIC_TRANSIT')
  const taxi = options.find((option) => option.mode === 'TAXI')

  if (walking && walking.distance <= 0.8 && walking.duration <= 14) return 'WALK'
  if (transit && transit.distance <= 4.5) return 'PUBLIC_TRANSIT'
  return taxi ? 'TAXI' : options[0]?.mode ?? 'WALK'
}

function toChoice(from: PlanNode, to: PlanNode, option: RouteOption): SelectedRouteChoice {
  return {
    segmentKey: segmentKey(from, to),
    mode: option.mode,
    fromNodeId: from.id,
    toNodeId: to.id,
    fromPoiName: from.place,
    toPoiName: to.place,
    duration: option.duration,
    distance: option.distance,
    priceEstimate: option.priceEstimate,
    transfers: option.transfers,
  }
}

export function MapColumn({ nodes, selectedRouteChoices, onRouteChoiceChange }: MapColumnProps) {
  const mapRef = useRef<HTMLDivElement>(null)
  const mapInstanceRef = useRef<AMap.Map | null>(null)
  const overlaysRef = useRef<unknown[]>([])
  const [routeSegments, setRouteSegments] = useState<RouteSegmentInfo[]>([])
  const [loadingRoutes, setLoadingRoutes] = useState(false)
  const [mapReady, setMapReady] = useState(false)

  const routeNodes = useMemo(() => nodes.filter((node) => !node.isTransit), [nodes])
  const nodesKey = routeNodes.map((node) => `${node.id}:${node.lnglat[0]},${node.lnglat[1]}`).join('|')
  const selectedModesKey = routeNodes
    .slice(0, -1)
    .map((node, index) => selectedRouteChoices[segmentKey(node, routeNodes[index + 1])]?.mode ?? '')
    .join('|')

  useEffect(() => {
    if (!mapRef.current || !window.AMap) return

    if (mapInstanceRef.current) {
      mapInstanceRef.current.destroy()
      mapInstanceRef.current = null
    }

    const map = new AMap.Map(mapRef.current, {
      zoom: 14,
      mapStyle: 'amap://styles/macaron',
      center: routeNodes[0]?.lnglat,
      features: ['bg', 'road', 'building', 'point'],
    })
    mapInstanceRef.current = map
    setMapReady(true)

    return () => {
      map.destroy()
      mapInstanceRef.current = null
      setMapReady(false)
    }
  }, [nodesKey])

  useEffect(() => {
    const map = mapInstanceRef.current
    if (!map || !mapReady) return

    if (overlaysRef.current.length > 0) {
      map.remove(overlaysRef.current)
      overlaysRef.current = []
    }

    const newOverlays: unknown[] = []

    routeNodes.forEach((node, index) => {
      const marker = new AMap.Marker({
        position: node.lnglat,
        map,
        anchor: 'center',
        content: `<div style="
          display:grid; place-items:center;
          width:32px; height:32px;
          border-radius:50%;
          background:#f7cd67;
          color:#725d42;
          font-weight:900; font-size:14px;
          font-family:Nunito,sans-serif;
          box-shadow:0 3px 0 #dba90e, 0 6px 12px rgba(61,52,40,0.2);
          border:2.5px solid #fff;
        ">${index + 1}</div>`,
        offset: new AMap.Pixel(-16, -16),
      })
      newOverlays.push(marker)
    })

    routeNodes.slice(0, -1).forEach((node, index) => {
      const nextNode = routeNodes[index + 1]
      const choice = selectedRouteChoices[segmentKey(node, nextNode)]
      const style = routeStyles[choice?.mode ?? 'WALK']
      const path = [node.lnglat, nextNode.lnglat]

      const outlinePolyline = new AMap.Polyline({
        path,
        strokeColor: style.line,
        strokeWeight: 10,
        strokeOpacity: 0.18,
        lineJoin: 'round',
        lineCap: 'round',
        map,
      })
      const polyline = new AMap.Polyline({
        path,
        strokeColor: style.line,
        strokeWeight: 5,
        strokeStyle: choice?.mode === 'WALK' ? 'dashed' : 'solid',
        strokeOpacity: 0.88,
        lineJoin: 'round',
        lineCap: 'round',
        map,
      })
      newOverlays.push(outlinePolyline, polyline)
    })

    overlaysRef.current = newOverlays

    if (routeNodes.length > 0) {
      map.setFitView(undefined, false, [60, 60, 60, 60], 16)
    }
  }, [mapReady, nodesKey, selectedModesKey])

  useEffect(() => {
    if (!window.AMap || routeNodes.length < 2) {
      setRouteSegments([])
      return
    }

    setLoadingRoutes(true)
    const segments: RouteSegmentInfo[] = Array.from({ length: routeNodes.length - 1 }, () => ({
      walking: null,
      transit: null,
      driving: null,
    }))
    let completed = 0
    const total = (routeNodes.length - 1) * 3

    function checkDone() {
      completed += 1
      if (completed >= total) {
        setRouteSegments([...segments])
        setLoadingRoutes(false)
      }
    }

    for (let index = 0; index < routeNodes.length - 1; index += 1) {
      const origin = routeNodes[index].lnglat
      const destination = routeNodes[index + 1].lnglat

      const walking = new AMap.Walking({ autoFitView: false, hideMarkers: true })
      walking.search(origin, destination, (status, result) => {
        if (status === 'complete' && result.routes?.[0]) {
          segments[index].walking = {
            duration: Math.round(result.routes[0].time / 60),
            distance: Math.round(result.routes[0].distance / 100) / 10,
          }
        }
        checkDone()
      })

      const transit = new AMap.Transfer({ city: '上海', autoFitView: false, hideMarkers: true })
      transit.search(origin, destination, (status, result) => {
        if (status === 'complete' && result.plans?.[0]) {
          segments[index].transit = {
            duration: Math.round(result.plans[0].time / 60),
            transfers: Math.max(
              0,
              (result.plans[0].segments?.filter((segment) => segment.transit_mode === 'BUS' || segment.bus)?.length ?? 1) - 1,
            ),
          }
        }
        checkDone()
      })

      const driving = new AMap.Driving({ autoFitView: false, hideMarkers: true })
      driving.search(origin, destination, (status, result) => {
        if (status === 'complete' && result.routes?.[0]) {
          segments[index].driving = {
            duration: Math.round(result.routes[0].time / 60),
            distance: Math.round(result.routes[0].distance / 100) / 10,
          }
        }
        checkDone()
      })
    }
  }, [nodesKey])

  useEffect(() => {
    routeNodes.slice(0, -1).forEach((node, index) => {
      const nextNode = routeNodes[index + 1]
      const key = segmentKey(node, nextNode)
      if (selectedRouteChoices[key]) return

      const options = optionsForSegment(node, nextNode, routeSegments[index])
      const option = options.find((item) => item.mode === defaultMode(options)) ?? options[0]
      if (option) onRouteChoiceChange(key, toChoice(node, nextNode, option))
    })
  }, [nodesKey, routeSegments, selectedRouteChoices, onRouteChoiceChange])

  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-y-auto overscroll-contain custom-scrollbar pb-[100px] md:pb-0">
      <Card className="relative flex flex-col shrink-0 p-3 px-4 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] transition-all duration-200 overflow-visible hover:!translate-y-0">
        <div
          ref={mapRef}
          className="w-full h-[300px] rounded-[20px] border-2 border-animal-border overflow-hidden shadow-[0_3px_0_0_#d4c9b4]"
        />
        {!window.AMap && (
          <div className="flex flex-col items-center justify-center absolute inset-3 bg-[#eef7df] rounded-[20px] text-center p-6">
            <div className="text-3xl mb-2">地图</div>
            <p className="text-[#794f27] font-black text-sm">地图加载中...</p>
            <p className="text-[#9a835a] text-xs mt-1">请确认高德地图 API Key 已正确配置。</p>
          </div>
        )}
      </Card>

      <Card className="flex flex-col shrink-0 p-3 px-4 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] hover:!translate-y-0">
        <div className="relative grid gap-1.5 p-[10px_12px] border-2 border-animal-border rounded-[16px] bg-[#eef7df]/60 before:content-[''] before:absolute before:top-5 before:bottom-5 before:left-[23px] before:w-[3px] before:rounded-full before:bg-[#19c8b9]">
          {routeNodes.map((node, index) => (
            <div className="relative z-10 flex items-center gap-2 min-w-0" key={node.id}>
              <span className="grid place-items-center w-5 h-5 rounded-full bg-[#82d5bb] text-[#0f332e] text-[10px] font-black shadow-[0_2px_0_#11a89b] shrink-0">
                {index + 1}
              </span>
              <p className="m-0 min-w-0 px-[8px] py-0.5 rounded-full bg-[#fff9e8] text-[#725d42] text-[10px] font-black truncate">{node.place}</p>
            </div>
          ))}
        </div>
      </Card>

      {routeNodes.slice(0, -1).map((node, index) => {
        const nextNode = routeNodes[index + 1]
        const key = segmentKey(node, nextNode)
        const segment = routeSegments[index]
        const isLoading = loadingRoutes && !segment
        const options = optionsForSegment(node, nextNode, segment)
        const selected = selectedRouteChoices[key]

        return (
          <Card
            className="flex flex-col max-[640px]:px-4 max-[640px]:py-[15px] shrink-0 p-4 px-5 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] transition-all duration-200 overflow-visible last:border-b-0 hover:!translate-y-0"
            key={`route-${node.id}-${nextNode.id}`}
          >
            <article className="flex flex-col min-w-0 flex-1">
              <div className="flex items-center justify-between gap-2.5 min-w-0 max-[640px]:flex-col max-[640px]:items-stretch">
                <strong className="min-w-0 overflow-hidden text-[#794f27] text-sm font-black text-ellipsis whitespace-nowrap">
                  {node.time} 到 {nextNode.time}
                </strong>
                <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#e6f9f6] text-[#11a89b] text-[11px] font-black shrink-0 whitespace-nowrap max-[640px]:self-start">
                  可选出行
                </span>
              </div>
              <h3 className="mt-1.5 mb-0 text-[#794f27] text-lg font-black leading-snug">
                {node.place} 到 {nextNode.place}
              </h3>

              {isLoading ? (
                <div className="flex flex-col gap-2 mt-3">
                  {[1, 2, 3].map((item) => (
                    <div key={item} className="h-[48px] rounded-[18px] bg-[#f0e8d8] animate-pulse" />
                  ))}
                </div>
              ) : (
                <div className="grid gap-2 mt-3">
                  {options.map((option) => {
                    const style = routeStyles[option.mode]
                    const active = selected?.mode === option.mode
                    return (
                      <button
                        type="button"
                        key={option.mode}
                        onClick={() => onRouteChoiceChange(key, toChoice(node, nextNode, option))}
                        className={`grid grid-cols-[minmax(0,1fr)_auto] items-center gap-3 text-left rounded-[18px] border-2 px-3 py-2 transition-all ${
                          active ? 'translate-y-[1px]' : 'hover:-translate-y-0.5'
                        }`}
                        style={{
                          background: active ? style.bg : '#fff9e8',
                          borderColor: active ? style.border : '#c4b89e',
                          boxShadow: active ? `0 2px 0 0 ${style.shadow}` : '0 3px 0 0 #d4c9b4',
                          color: style.text,
                        }}
                        aria-pressed={active}
                      >
                        <span className="min-w-0">
                          <span className="flex items-center gap-2">
                            <span className="text-sm font-black">{option.label}</span>
                            {active && (
                              <span className="rounded-full bg-white/70 px-2 py-0.5 text-[10px] font-black text-[#794f27]">已选</span>
                            )}
                          </span>
                          <span className="mt-0.5 block text-[11px] font-bold text-[#8a7b66]">{option.detail}</span>
                        </span>
                        <span className="text-right shrink-0">
                          <span className="block text-sm font-black text-[#794f27]">{option.duration} 分钟</span>
                          <span className="block text-[11px] font-bold text-[#9a835a]">{option.distance.toFixed(1)} km</span>
                          {option.priceEstimate && (
                            <span className="block text-[11px] font-black text-[#d9791c]">{option.priceEstimate}</span>
                          )}
                        </span>
                      </button>
                    )
                  })}
                </div>
              )}

              {selected?.mode === 'TAXI' && (
                <p className="mt-3 mb-0 rounded-[16px] border-2 border-[#f7cd67] bg-[#fff3c4] px-3 py-2 text-[12px] font-black text-[#794f27] shadow-[0_2px_0_0_#dba90e]">
                  打车会在“确定方案”时一并模拟处理网约车订单。
                </p>
              )}
            </article>
          </Card>
        )
      })}
    </div>
  )
}
