import type { AgentPlanStep, AgentOrderIntent } from '../api/agent'
import type { PlanNode, SelectedRouteChoice } from '../types/plan'

export function timelineKey(step: AgentPlanStep) {
  return step.orderIntentId?.trim() || step.poiId?.trim() || step.poiName?.trim()
}

export function minutesFromTime(value?: string) {
  if (!value) return 14 * 60
  const [hour = '14', minute = '0'] = value.split(':')
  return Number.parseInt(hour, 10) * 60 + Number.parseInt(minute, 10)
}

export function formatMinutes(value: number) {
  const hour = Math.floor(value / 60)
  const minute = value % 60
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
}

export function distanceKm(from: [number, number], to: [number, number]) {
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

export function transitDuration(distance: number) {
  if (distance <= 0.8) return Math.max(6, Math.round((distance / 4.5) * 60))
  if (distance <= 2.2) return Math.max(12, Math.round((distance / 18) * 60) + 8)
  return Math.max(18, Math.round((distance / 24) * 60) + 10)
}

export function transitMode(distance: number, duration: number) {
  if (distance <= 0.8 && duration <= 14) return '步行'
  if (distance <= 2.2) return '公交/地铁'
  return '地铁'
}

export function routeChoiceLabel(choice: SelectedRouteChoice) {
  if (choice.mode === 'WALK') return '步行'
  if (choice.mode === 'PUBLIC_TRANSIT') return '公交/地铁'
  return '打车'
}

export function rideOrderIntentId(choice: SelectedRouteChoice) {
  const clean = `${choice.fromNodeId}-${choice.toNodeId}`.replace(/[^a-zA-Z0-9_-]/g, '-')
  return `RIDE-${clean}`
}

export function transitStepFromChoice(
  choice: SelectedRouteChoice,
  fromStep: AgentPlanStep,
  toStep: AgentPlanStep,
  defaultHeadcount: number
): AgentPlanStep {
  const start = fromStep.endTime || fromStep.startTime || toStep.startTime
  const startMinutes = minutesFromTime(start)
  const endTime = formatMinutes(startMinutes + choice.duration)
  const label = routeChoiceLabel(choice)
  const isTaxi = choice.mode === 'TAXI'

  return {
    action: `${label} ${choice.duration} 分钟`,
    bookingStatus: isTaxi ? '待叫车' : '无需下单',
    note: isTaxi
      ? `模拟网约车：${choice.fromPoiName} 到 ${choice.toPoiName}，${choice.priceEstimate || '费用待确认'}。`
      : `${label}衔接 ${choice.distance.toFixed(1)} km。`,
    phase: 'TRANSIT',
    poiId: isTaxi ? rideOrderIntentId(choice) : '',
    poiName: `${choice.fromPoiName} 到 ${choice.toPoiName}`,
    durationMinutes: choice.duration,
    startTime: start,
    endTime,
    lnglat: toStep.lnglat || fromStep.lnglat,
    audience: '路线衔接',
    reason: isTaxi ? '用户已选择打车，确认方案时模拟网约车订单。' : `${label}方案由地图列选择。`,
    budget: isTaxi ? `打车约 ${choice.priceEstimate || 'CNY 14-24'}` : choice.mode === 'WALK' ? '免费' : '交通约 CNY 2-8',
    headcount: fromStep.headcount || toStep.headcount || defaultHeadcount,
    constraints: '',
    executionStatus: isTaxi ? 'PENDING' : '',
    orderIntentId: isTaxi ? rideOrderIntentId(choice) : '',
    isTransit: true,
    transportMode: choice.mode,
    distanceKm: choice.distance,
    fromPoiName: choice.fromPoiName,
    toPoiName: choice.toPoiName,
  }
}

export function applySelectedRouteChoices(
  timeline: AgentPlanStep[],
  planNodes: PlanNode[],
  selectedRouteChoices: Record<string, SelectedRouteChoice>,
  defaultHeadcount: number
) {
  if (Object.keys(selectedRouteChoices).length === 0) return timeline

  const nodeToStep = new Map<string, AgentPlanStep>()
  const stepToNodeId = new Map<AgentPlanStep, string>()
  planNodes
    .filter((node) => !node.isTransit)
    .forEach((node) => {
      const step = timeline.find((entry) => {
        if (entry.isTransit) return false
        return (
          entry.orderIntentId === node.orderIntentId ||
          entry.poiId === node.poiId ||
          entry.poiName === node.place ||
          entry.segmentId === node.segmentId
        )
      })
      if (step) {
        nodeToStep.set(node.id, step)
        stepToNodeId.set(step, node.id)
      }
    })

  const result: AgentPlanStep[] = []
  timeline.forEach((step) => {
    if (step.isTransit) {
      const choice = Object.values(selectedRouteChoices).find(
        (item) => item.fromPoiName === step.fromPoiName && item.toPoiName === step.toPoiName,
      )
      if (!choice) {
        result.push(step)
        return
      }
      const fromStep = nodeToStep.get(choice.fromNodeId)
      const toStep = nodeToStep.get(choice.toNodeId)
      result.push(fromStep && toStep ? transitStepFromChoice(choice, fromStep, toStep, defaultHeadcount) : step)
      return
    }

    result.push(step)
    const fromNodeId = stepToNodeId.get(step)
    if (!fromNodeId) return
    const nextChoice = Object.values(selectedRouteChoices).find((choice) => choice.fromNodeId === fromNodeId)
    if (!nextChoice) return
    const hasExistingTransit = timeline.some(
      (entry) => entry.isTransit && entry.fromPoiName === nextChoice.fromPoiName && entry.toPoiName === nextChoice.toPoiName,
    )
    if (hasExistingTransit) return
    const toStep = nodeToStep.get(nextChoice.toNodeId)
    if (toStep) result.push(transitStepFromChoice(nextChoice, step, toStep, defaultHeadcount))
  })

  return result
}

export function rebuildTimelineWithTransit(
  nodes: PlanNode[],
  selectedRouteChoices: Record<string, SelectedRouteChoice>
) {
  const businessNodes = nodes.filter((node) => !node.isTransit)
  if (businessNodes.length === 0) return []

  // 1. Calculate plan start time (minimum start time of all business nodes)
  let planStartTime = 14 * 60 // fallback to 14:00
  if (businessNodes.length > 0) {
    const times = businessNodes.map((n) => {
      const startPart = n.startTime || n.time.split('-')[0]
      return minutesFromTime(startPart)
    })
    planStartTime = Math.min(...times)
  }

  // 2. Adjust business nodes' times sequentially while preserving their durations
  let currentMinutes = planStartTime
  const adjustedBusinessNodes: PlanNode[] = []

  businessNodes.forEach((node, index) => {
    // Calculate original duration
    const startMin = minutesFromTime(node.startTime || node.time.split('-')[0])
    const endMin = minutesFromTime(node.endTime || node.time.split('-')[1] || node.time.split('-')[0])
    const duration = Math.max(0, endMin - startMin)

    const newStart = currentMinutes
    const newEnd = currentMinutes + duration

    const hasRange = node.time.includes('-') || (node.startTime && node.endTime && node.startTime !== node.endTime)
    const newStartTimeStr = formatMinutes(newStart)
    const newEndTimeStr = formatMinutes(newEnd)
    const newTimeStr = hasRange ? `${newStartTimeStr}-${newEndTimeStr}` : newStartTimeStr

    const adjustedNode: PlanNode = {
      ...node,
      time: newTimeStr,
      startTime: newStartTimeStr,
      endTime: newEndTimeStr,
    }

    adjustedBusinessNodes.push(adjustedNode)

    const next = businessNodes[index + 1]
    if (next) {
      // Calculate transit distance and duration to next node
      const distance = distanceKm(node.lnglat, next.lnglat)
      const key = `${node.id}->${next.id}`
      const choice = selectedRouteChoices[key]
      const transitDur = choice ? choice.duration : transitDuration(distance)
      currentMinutes = newEnd + transitDur
    }
  })

  // 3. Rebuild the final array with transit nodes in between
  const rebuilt: PlanNode[] = []
  adjustedBusinessNodes.forEach((node, index) => {
    rebuilt.push(node)
    const next = adjustedBusinessNodes[index + 1]
    if (!next) return

    const start = minutesFromTime(node.endTime)
    const distance = distanceKm(node.lnglat, next.lnglat)
    const key = `${node.id}->${next.id}`
    const choice = selectedRouteChoices[key]
    const duration = choice ? choice.duration : transitDuration(distance)
    const mode = choice ? routeChoiceLabel(choice) : transitMode(distance, duration)
    rebuilt.push({
      id: `transit-${node.id}-${next.id}`,
      time: `${formatMinutes(start)}-${formatMinutes(start + duration)}`,
      title: `${mode} ${duration} 分钟`,
      place: `${node.place} → ${next.place}`,
      lnglat: next.lnglat,
      audience: '路线衔接',
      reason: choice
        ? `${mode}衔接约 ${distance.toFixed(1)}km，预计 ${duration} 分钟；由路线列选择。`
        : `${mode}约 ${distance.toFixed(1)}km，预计 ${duration} 分钟；交通不会挤占停留时间。`,
      budget: mode === '步行' ? '免费' : choice?.mode === 'TAXI' ? `交通约 ${choice.priceEstimate || 'CNY 14-24'}` : '交通约 CNY 0-8',
      status: mode,
      details: `从 ${node.place} 到 ${next.place}`,
      startTime: formatMinutes(start),
      endTime: formatMinutes(start + duration),
      isTransit: true,
      transportMode: choice ? choice.mode : mode,
      distanceKm: distance,
      fromPoiName: node.place,
      toPoiName: next.place,
    })
  })

  return rebuilt
}


export function orderedTimelineForCurrentNodes(
  planNodes: PlanNode[],
  currentTimeline: AgentPlanStep[],
  selectedRouteChoices: Record<string, SelectedRouteChoice>,
  defaultHeadcount: number
): AgentPlanStep[] {
  const entries = new Map<string, AgentPlanStep>()
  currentTimeline.forEach((step) => {
    const key = timelineKey(step)
    if (key) entries.set(key, step)
  })

  const used = new Set<string>()
  const ordered: AgentPlanStep[] = []
  planNodes.forEach((node) => {
    const key = node.orderIntentId?.trim() || node.id?.trim() || node.place?.trim()
    const step = key ? entries.get(key) : undefined
    if (key && step) used.add(key)
    if (step) {
      ordered.push({
        ...step,
        action: node.title,
        poiId: node.poiId || step.poiId,
        poiName: node.place,
        startTime: node.startTime || step.startTime,
        endTime: node.endTime || step.endTime,
        lnglat: node.lnglat,
        audience: node.audience,
        reason: node.reason,
        budget: node.budget,
        headcount: node.headcount || step.headcount,
        constraints: node.constraints || step.constraints,
        isTransit: node.isTransit || step.isTransit,
        transportMode: node.transportMode || step.transportMode,
        distanceKm: node.distanceKm ?? step.distanceKm,
        fromPoiName: node.fromPoiName || step.fromPoiName,
        toPoiName: node.toPoiName || step.toPoiName,
      })
    }
  })

  const fallbackSteps = planNodes
    .filter((node) => !node.isTransit)
    .filter((node) => {
      const key = node.orderIntentId?.trim() || node.id?.trim() || node.place?.trim()
      return !key || !used.has(key)
    })
    .map((node): AgentPlanStep => {
      return {
        action: node.title,
        bookingStatus: node.status,
        note: node.details,
        phase: node.transportMode ? 'TRANSIT' : node.orderIntentId ? 'DINING' : 'LEISURE',
        poiId: node.poiId || '',
        poiName: node.place,
        durationMinutes: Math.max(30, minutesFromTime(node.endTime) - minutesFromTime(node.startTime)),
        startTime: node.startTime,
        endTime: node.endTime,
        lnglat: node.lnglat,
        audience: node.audience,
        reason: node.reason,
        budget: node.budget,
        headcount: node.headcount,
        constraints: node.constraints,
        executionStatus: node.executionStatus,
        orderIntentId: node.orderIntentId,
        isTransit: node.isTransit,
        transportMode: node.transportMode,
        distanceKm: node.distanceKm,
        fromPoiName: node.fromPoiName,
        toPoiName: node.toPoiName,
      }
    })

  return applySelectedRouteChoices([...ordered, ...fallbackSteps], planNodes, selectedRouteChoices, defaultHeadcount)
}

export function orderIntentsForCurrentTimeline(
  planNodes: PlanNode[],
  currentTimeline: AgentPlanStep[],
  selectedRouteChoices: Record<string, SelectedRouteChoice>,
  confirmHeadcount: number,
  defaultHeadcount: number
): AgentOrderIntent[] {
  return orderedTimelineForCurrentNodes(planNodes, currentTimeline, selectedRouteChoices, defaultHeadcount)
    .filter((step) => {
      if (step.isTransit) return step.transportMode === 'TAXI' && Boolean(step.orderIntentId)
      return Boolean(step.poiId) && ['DINING', 'DRINKS', 'ACTIVITY'].includes(step.phase)
    })
    .map((step, index) => ({
      orderIntentId: step.orderIntentId || `LOCAL-${step.poiId}-${index}`,
      type: step.isTransit ? 'RIDE_HAIL' : step.phase === 'ACTIVITY' ? 'BOOK_TICKET' : 'RESERVE_TABLE',
      poiId: step.poiId,
      poiName: step.poiName,
      headcount: confirmHeadcount,
      targetTime: step.startTime || '',
      status: 'PENDING',
    }))
}
