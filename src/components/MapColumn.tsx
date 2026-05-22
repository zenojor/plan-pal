import { Card } from 'animal-island-ui'
import { useEffect, useRef, useState } from 'react'
import type { PlanNode, RouteSegmentInfo } from '../types/plan'

type MapColumnProps = {
  nodes: PlanNode[]
}

export function MapColumn({ nodes }: MapColumnProps) {
  const mapRef = useRef<HTMLDivElement>(null)
  const mapInstanceRef = useRef<AMap.Map | null>(null)
  const overlaysRef = useRef<unknown[]>([])
  const [routeSegments, setRouteSegments] = useState<RouteSegmentInfo[]>([])
  const [loadingRoutes, setLoadingRoutes] = useState(false)
  const [mapReady, setMapReady] = useState(false)

  const nodesKey = nodes.map((node) => `${node.id}:${node.lnglat[0]},${node.lnglat[1]}`).join('|')

  useEffect(() => {
    if (!mapRef.current || !window.AMap) return

    if (mapInstanceRef.current) {
      mapInstanceRef.current.destroy()
      mapInstanceRef.current = null
    }

    const map = new AMap.Map(mapRef.current, {
      zoom: 14,
      mapStyle: 'amap://styles/macaron',
      center: nodes[0]?.lnglat,
      features: ['bg', 'road', 'building', 'point'],
    })
    mapInstanceRef.current = map
    setMapReady(true)

    return () => {
      map.destroy()
      mapInstanceRef.current = null
      setMapReady(false)
    }
  }, [nodes])

  useEffect(() => {
    const map = mapInstanceRef.current
    if (!map || !mapReady) return

    if (overlaysRef.current.length > 0) {
      map.remove(overlaysRef.current)
      overlaysRef.current = []
    }

    const newOverlays: unknown[] = []

    nodes.forEach((node, index) => {
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

    if (nodes.length > 1) {
      const polyline = new AMap.Polyline({
        path: nodes.map((node) => node.lnglat),
        strokeColor: '#19c8b9',
        strokeWeight: 5,
        strokeStyle: 'solid',
        strokeOpacity: 0.85,
        lineJoin: 'round',
        lineCap: 'round',
        map,
      })
      newOverlays.push(polyline)

      const outlinePolyline = new AMap.Polyline({
        path: nodes.map((node) => node.lnglat),
        strokeColor: '#11a89b',
        strokeWeight: 8,
        strokeStyle: 'solid',
        strokeOpacity: 0.2,
        lineJoin: 'round',
        lineCap: 'round',
        map,
      })
      newOverlays.push(outlinePolyline)
    }

    overlaysRef.current = newOverlays

    if (nodes.length > 0) {
      map.setFitView(undefined, false, [60, 60, 60, 60], 16)
    }
  }, [mapReady, nodes, nodesKey])

  useEffect(() => {
    if (!window.AMap || nodes.length < 2) {
      return
    }

    window.setTimeout(() => setLoadingRoutes(true), 0)
    const segments: RouteSegmentInfo[] = Array.from({ length: nodes.length - 1 }, () => ({
      walking: null,
      transit: null,
      driving: null,
    }))
    let completed = 0
    const total = (nodes.length - 1) * 3

    function checkDone() {
      completed += 1
      if (completed >= total) {
        setRouteSegments([...segments])
        setLoadingRoutes(false)
      }
    }

    for (let index = 0; index < nodes.length - 1; index += 1) {
      const origin = nodes[index].lnglat
      const destination = nodes[index + 1].lnglat

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
  }, [nodes, nodesKey])

  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-y-auto overscroll-contain custom-scrollbar pb-[100px] md:pb-0">
      <Card className="flex flex-col shrink-0 p-3 px-4 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] transition-all duration-200 overflow-visible hover:!translate-y-0">
        <div
          ref={mapRef}
          className="w-full h-[300px] rounded-[20px] border-2 border-animal-border overflow-hidden shadow-[0_3px_0_0_#d4c9b4]"
        />
        {!window.AMap && (
          <div className="flex flex-col items-center justify-center absolute inset-0 bg-[#eef7df] rounded-[20px] text-center p-6">
            <div className="text-3xl mb-2">地图</div>
            <p className="text-[#794f27] font-black text-sm">地图加载中...</p>
            <p className="text-[#9a835a] text-xs mt-1">请确认高德地图 API Key 已正确配置。</p>
          </div>
        )}
      </Card>

      <Card className="flex flex-col shrink-0 p-3 px-4 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] hover:!translate-y-0">
        <div className="relative grid gap-1.25 p-[8px_10px] border-2 border-animal-border rounded-[16px] bg-[#eef7df]/60 before:content-[''] before:absolute before:top-5 before:bottom-5 before:left-[21px] before:w-[3px] before:rounded-full before:bg-[#19c8b9]">
          {nodes.map((node, index) => (
            <div className="relative z-10 flex items-center gap-2" key={node.id}>
              <span className="grid place-items-center w-5 h-5 rounded-full bg-[#82d5bb] text-[#0f332e] text-[10px] font-black shadow-[0_2px_0_#11a89b]">
                {index + 1}
              </span>
              <p className="m-0 px-[8px] py-0.5 rounded-full bg-[#fff9e8] text-[#725d42] text-[10px] font-black">{node.place}</p>
            </div>
          ))}
        </div>
      </Card>

      {nodes.slice(0, -1).map((node, index) => {
        const nextNode = nodes[index + 1]
        const segment = routeSegments[index]
        const isLoading = loadingRoutes && !segment

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
                  移动
                </span>
              </div>
              <h3 className="mt-1.25 mb-0 text-[#794f27] text-lg font-black leading-snug">
                {node.place} 到 {nextNode.place}
              </h3>

              {isLoading ? (
                <div className="flex flex-col gap-2 mt-3">
                  {[1, 2, 3].map((item) => (
                    <div key={item} className="h-[34px] rounded-full bg-[#f0e8d8] animate-pulse" />
                  ))}
                </div>
              ) : segment ? (
                <div className="flex flex-col gap-1.5 mt-3">
                  {segment.walking && (
                    <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-[#e6f9f6] border border-[#82d5bb]/40">
                      <span className="text-[#11a89b] text-[12px] font-black">步行</span>
                      <span className="text-[#725d42] text-[12px] font-bold">{segment.walking.duration} 分钟</span>
                      <span className="text-[#9a835a] text-[11px]">{segment.walking.distance} km</span>
                    </div>
                  )}
                  {segment.transit && (
                    <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-[#e8ecfd] border border-[#889df0]/30">
                      <span className="text-[#5a6fbf] text-[12px] font-black">公交</span>
                      <span className="text-[#725d42] text-[12px] font-bold">{segment.transit.duration} 分钟</span>
                      <span className="text-[#9a835a] text-[11px]">换乘 {segment.transit.transfers} 次</span>
                    </div>
                  )}
                  {segment.driving && (
                    <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-[#fff3c4] border border-[#f7cd67]/40">
                      <span className="text-[#9a835a] text-[12px] font-black">驾车</span>
                      <span className="text-[#725d42] text-[12px] font-bold">{segment.driving.duration} 分钟</span>
                      <span className="text-[#9a835a] text-[11px]">{segment.driving.distance} km</span>
                    </div>
                  )}
                  {!segment.walking && !segment.transit && !segment.driving && (
                    <p className="mt-1 text-[#9a835a] text-sm font-semibold">暂时还没有拿到路线数据。</p>
                  )}
                </div>
              ) : (
                <p className="mt-2 text-[#9a835a] text-sm font-semibold">路线数据加载中...</p>
              )}
            </article>
          </Card>
        )
      })}
    </div>
  )
}
