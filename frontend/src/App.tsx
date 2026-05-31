import { useEffect, useRef, useState } from 'react'
import type { DragEvent, FormEvent } from 'react'
import { Button } from 'animal-island-ui'
import {
  confirmPlan,
  mapPlanResponseToNodes,
  requestPlanStream,
  requestPlanChatStream,
} from './api/agent'
import type {
  AgentPlanChatRequest,
  AgentOrderIntent,
  AgentPlanPatch,
  AgentPlanResponse,
  AgentPlanStreamEvent,
  AgentPlanStep,
} from './api/agent'
import { ColumnHeader } from './components/ColumnHeader'
import { ColumnPicker } from './components/ColumnPicker'
import { ConfirmOrderModal } from './components/ConfirmOrderModal'
import { DetailsColumn } from './components/DetailsColumn'
import { DevColumn } from './components/DevColumn'
import { IntroScreen } from './components/IntroScreen'
import { MapColumn } from './components/MapColumn'
import { MerchantColumn } from './components/MerchantColumn'
import { MobileBottomNav } from './components/MobileBottomNav'
import { PlanPalChatColumn } from './components/PlanPalChatColumn'
import { PlanningHeader } from './components/PlanningHeader'
import { PuzzleColumn } from './components/PuzzleColumn'
import { API_BASE_URL } from './config/api'
import {
  basePlan,
  columnMeta,
  examplePrompts,
} from './data/planData'
import './index.css'
import type { ChatMessage, ColumnId, PlanNode, Stage } from './types/plan'

function App() {
  const [stage, setStage] = useState<Stage>('intro')
  const [requirement, setRequirement] = useState('')
  const [planSummary, setPlanSummary] = useState('')
  const [draft, setDraft] = useState('')
  const [planNodes, setPlanNodes] = useState<PlanNode[]>(basePlan)
  const [columns, setColumns] = useState<ColumnId[]>(['chat', 'puzzle'])
  const [draggingColumn, setDraggingColumn] = useState<ColumnId | null>(null)
  const [draggingNodeId, setDraggingNodeId] = useState<string | null>(null)
  const [dragOverColumn, setDragOverColumn] = useState<ColumnId | null>(null)
  const [dragOverNodeId, setDragOverNodeId] = useState<string | null>(null)
  const [isColumnMenuOpen, setIsColumnMenuOpen] = useState(false)
  const [editingNodeId, setEditingNodeId] = useState<string | null>(null)
  const [selectedMerchantPlace, setSelectedMerchantPlace] = useState<string | null>(null)
  const [nodeDraft, setNodeDraft] = useState('')
  const [activeMobileTab, setActiveMobileTab] = useState<ColumnId>('puzzle')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isConfirming, setIsConfirming] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [currentPlan, setCurrentPlan] = useState<AgentPlanResponse | null>(null)
  const [currentTimeline, setCurrentTimeline] = useState<AgentPlanStep[]>([])
  const [chatDraft, setChatDraft] = useState('')
  const [sseEvents, setSseEvents] = useState<AgentPlanStreamEvent[]>([])
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([])
  const [isConfirmModalOpen, setIsConfirmModalOpen] = useState(false)
  const [confirmHeadcount, setConfirmHeadcount] = useState(1)
  const [failedOrderIds, setFailedOrderIds] = useState<string[]>([])
  const columnContainerRef = useRef<HTMLDivElement>(null)
  const streamCleanupRef = useRef<(() => void) | null>(null)
  const isConsultModeRef = useRef(false)
  const isClarificationFlowRef = useRef(false)
  const activityMessageIdRef = useRef<string | null>(null)

  const closedColumns = (Object.keys(columnMeta) as ColumnId[]).filter(
    (column) => column !== 'puzzle' && !columns.includes(column),
  )

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (
        columnContainerRef.current &&
        !columnContainerRef.current.contains(event.target as Node)
      ) {
        setIsColumnMenuOpen(false)
      }
    }

    if (isColumnMenuOpen) {
      document.addEventListener('mousedown', handleClickOutside)
    }

    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
    }
  }, [isColumnMenuOpen])

  useEffect(() => {
    return () => {
      streamCleanupRef.current?.()
    }
  }, [])

  function timelineKey(step: AgentPlanStep) {
    return step.orderIntentId?.trim() || step.poiId?.trim() || step.poiName?.trim()
  }

  function minutesFromTime(value?: string) {
    if (!value) return 14 * 60
    const [hour = '14', minute = '0'] = value.split(':')
    return Number.parseInt(hour, 10) * 60 + Number.parseInt(minute, 10)
  }

  function formatMinutes(value: number) {
    const hour = Math.floor(value / 60)
    const minute = value % 60
    return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
  }

  function distanceKm(from: [number, number], to: [number, number]) {
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

  function transitDuration(distance: number) {
    if (distance <= 0.8) return Math.max(6, Math.round((distance / 4.5) * 60))
    if (distance <= 2.2) return Math.max(12, Math.round((distance / 18) * 60) + 8)
    return Math.max(18, Math.round((distance / 24) * 60) + 10)
  }

  function transitMode(distance: number, duration: number) {
    if (distance <= 0.8 && duration <= 14) return '步行'
    if (distance <= 2.2) return '公交/地铁'
    return '地铁'
  }

  function rebuildTimelineWithTransit(nodes: PlanNode[]) {
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
        const transitDur = transitDuration(distance)
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
      const duration = transitDuration(distance)
      const mode = transitMode(distance, duration)
      rebuilt.push({
        id: `transit-${node.id}-${next.id}`,
        time: `${formatMinutes(start)}-${formatMinutes(start + duration)}`,
        title: `${mode} ${duration} 分钟`,
        place: `${node.place} → ${next.place}`,
        lnglat: next.lnglat,
        audience: '路线衔接',
        reason: `${mode}约 ${distance.toFixed(1)}km，预计 ${duration} 分钟；交通不会挤占停留时间。`,
        budget: mode === '步行' ? '免费' : '交通约 CNY 0-8',
        status: mode,
        details: `从 ${node.place} 到 ${next.place}`,
        startTime: formatMinutes(start),
        endTime: formatMinutes(start + duration),
        isTransit: true,
        transportMode: mode,
        distanceKm: distance,
        fromPoiName: node.place,
        toPoiName: next.place,
      })
    })

    return rebuilt
  }

  function orderedTimelineForCurrentNodes() {
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
      .map((node): AgentPlanStep => ({
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
      }))
    return [...ordered, ...fallbackSteps]
  }

  function orderIntentsForCurrentTimeline(): AgentOrderIntent[] {
    return orderedTimelineForCurrentNodes()
      .filter((step) => !step.isTransit && Boolean(step.poiId) && ['DINING', 'DRINKS', 'ACTIVITY'].includes(step.phase))
      .map((step, index) => ({
        orderIntentId: step.orderIntentId || `LOCAL-${step.poiId}-${index}`,
        type: step.phase === 'ACTIVITY' ? 'BOOK_TICKET' : 'RESERVE_TABLE',
        poiId: step.poiId,
        poiName: step.poiName,
        headcount: confirmHeadcount || currentPlan?.intent?.headcount || 1,
        targetTime: step.startTime || '',
        status: 'PENDING',
      }))
  }

  function appendOrUpdateSseEvent(streamEvent: AgentPlanStreamEvent) {
    appendAgentActivity(streamEvent)
    setSseEvents((prevEvents) => {
      if (prevEvents.length === 0) return [streamEvent]
      const lastEvent = prevEvents[prevEvents.length - 1]
      if (
        lastEvent.type === streamEvent.type &&
        lastEvent.step === streamEvent.step &&
        (streamEvent.type === 'THOUGHT' ||
          streamEvent.type === 'START' ||
          streamEvent.type === 'INTENT')
      ) {
        const updatedLast = {
          ...lastEvent,
          content: streamEvent.content,
          timeline: streamEvent.timeline ?? lastEvent.timeline,
          degradationNote: streamEvent.degradationNote ?? lastEvent.degradationNote,
        }
        return [...prevEvents.slice(0, -1), updatedLast]
      }
      return [...prevEvents, streamEvent]
    })
  }

  function hasTimeline(streamEvent: AgentPlanStreamEvent) {
    return Boolean(streamEvent.timeline?.length)
  }

  function stableSummaryFromTimeline(timeline?: AgentPlanStep[] | null) {
    const businessSteps = (timeline || []).filter((step) => !step.isTransit)
    const first = businessSteps[0] || timeline?.[0]
    const last = businessSteps[businessSteps.length - 1] || timeline?.[timeline.length - 1]
    if (first?.startTime && last?.endTime && businessSteps.length) {
      return `${first.startTime}-${last.endTime}，已生成 ${businessSteps.length} 个可执行节点`
    }
    return '方案已更新'
  }

  function headerSummaryFromStreamEvent(streamEvent: AgentPlanStreamEvent) {
    if (streamEvent.actionCard) return null
    if (!hasTimeline(streamEvent)) return null
    if (!['PLAN_ASSEMBLED', 'PLAN_FINISHED', 'FINISH'].includes(streamEvent.type)) return null

    const explicitSummary = streamEvent.summary?.trim()
    if (explicitSummary) return explicitSummary
    return stableSummaryFromTimeline(streamEvent.timeline)
  }

  function applyHeaderSummaryFromResponse(response: AgentPlanResponse) {
    if (!response.timeline.length) return
    setPlanSummary(response.summary?.trim() || stableSummaryFromTimeline(response.timeline))
  }

  function isChatOnlyFinishEvent(streamEvent: AgentPlanStreamEvent) {
    return (
      streamEvent.type === 'FINISH' &&
      (Boolean(streamEvent.actionCard) || !hasTimeline(streamEvent))
    )
  }

  function consumeStreamEvent(streamEvent: AgentPlanStreamEvent) {
    appendOrUpdateSseEvent(streamEvent)
    const headerSummary = headerSummaryFromStreamEvent(streamEvent)
    if (headerSummary) {
      setPlanSummary(headerSummary)
    }

    if (isChatOnlyFinishEvent(streamEvent)) {
      setChatMessages((messages) => messages.filter((m) => !m.isLoading))
      appendPlanPalMessage(streamEvent.content || streamEvent.actionCard?.description || '请在对话列继续操作。', {
        actionCard: streamEvent.actionCard ?? null,
        planPatch: streamEvent.planPatch ?? null,
      })
    }
  }

  function summarizeActivityEvent(streamEvent: AgentPlanStreamEvent) {
    const content = streamEvent.content || ''
    if (!content) return null
    const rawTool = content.includes(':') ? content.split(':')[0].trim() : streamEvent.type
    const detail = content.includes(':') ? content.slice(content.indexOf(':') + 1).trim() : content
    const toolLabels: Record<string, string> = {
      START: '启动任务',
      THOUGHT: '整理下一步',
      PLAN_STEP: '更新拼图',
      CANDIDATES_SEARCHING: '搜索候选',
      CANDIDATES_FOUND: '整理候选结果',
      AVAILABILITY_CHECKED: '检查可用性',
      SEGMENT_PLANNED: '确认一个拼图节点',
      PLAN_ASSEMBLED: '生成拼图方案',
      PLAN_FINISHED: '完成方案',
      'consult.start': '理解你的问题',
      'consult.respond': '整理约会方向',
      'consult.preference': '保存你的偏好',
      'router.decide': '理解意图并选择路线',
      'movie.search': '调用电影搜索工具',
      'poi.search': '调用地点搜索工具',
      'poi.search.dining': '调用餐饮搜索工具',
      'poi.search.replacement': '搜索替换候选',
      'poi.search.autoRecommendation': '搜索自动推荐',
      'candidate.rank': '排序候选',
      'candidate.select': '读取你的选择',
      'card.render': '生成选择卡片',
      'plan.edit': '更新行程拼图',
      'timeline.update': '刷新时间线',
      PlanningToolOrchestrator: '调用规划工具',
      'PlanningToolOrchestrator.collectCandidates': '搜索候选地点',
      'PlanningToolOrchestrator.checkAvailability': '检查可用性',
      ReplacementSearchEngine: '搜索替换候选',
      'ReplacementSearchEngine.findCandidates': '搜索替换候选',
      PlanEditorEngine: '更新行程拼图',
    }
    const toolKey =
      Object.keys(toolLabels).find((key) => rawTool === key || rawTool.startsWith(`${key} `)) || rawTool
    const label = toolLabels[toolKey] || toolLabels[streamEvent.type] || '处理进度'
    const safeDetail =
      streamEvent.type === 'ACTION' || streamEvent.type === 'OBSERVATION'
        ? undefined
        : detail === streamEvent.type
        ? undefined
        : detail
    const status =
      streamEvent.type === 'ERROR'
        ? 'error'
        : streamEvent.type === 'ACTION' || streamEvent.type === 'START'
        ? 'running'
        : 'done'
    return {
      id: `${streamEvent.step}-${streamEvent.type}-${rawTool}`,
      type: streamEvent.type,
      label,
      detail: safeDetail,
      status: status as 'running' | 'done' | 'error',
    }
  }

  function appendAgentActivity(streamEvent: AgentPlanStreamEvent) {
    const activityTypes = new Set([
      'START',
      'THOUGHT',
      'ACTION',
      'OBSERVATION',
      'PLAN_STEP',
      'CANDIDATES_SEARCHING',
      'CANDIDATES_FOUND',
      'AVAILABILITY_CHECKED',
      'SEGMENT_PLANNED',
      'PLAN_ASSEMBLED',
      'PLAN_FINISHED',
      'ERROR',
    ])

    if (streamEvent.type === 'FINISH') {
      const activityId = activityMessageIdRef.current
      if (activityId) {
        setChatMessages((messages) =>
          messages.map((message) =>
            message.id === activityId
              ? {
                  ...message,
                  content: 'PlanPal 已完成这轮处理',
                  activity: (message.activity || []).map((item) => ({ ...item, status: item.status === 'running' ? 'done' : item.status })),
                }
              : message,
          ),
        )
      }
      activityMessageIdRef.current = null
      return
    }

    if (!activityTypes.has(streamEvent.type)) return
    const item = summarizeActivityEvent(streamEvent)
    if (!item) return

    const activityId = activityMessageIdRef.current
    if (!activityId) {
      const newActivityId = `agent-activity-${Date.now()}`
      activityMessageIdRef.current = newActivityId
      setChatMessages((messages) => [
        ...messages,
        {
          id: newActivityId,
          role: 'planpal',
          content: 'PlanPal 正在处理这轮请求',
          activity: [item],
        },
      ])
      return
    }

    setChatMessages((messages) =>
      messages.map((message) => {
        if (message.id !== activityId) return message
        const existing = message.activity || []
        const nextExisting = existing.map((step) =>
          step.status === 'running' && item.status === 'running' ? { ...step, status: 'done' as const } : step,
        )
        const runningSameLabelIndex = nextExisting.findIndex(
          (step) => step.label === item.label && step.status === 'running' && item.status !== 'running',
        )
        if (runningSameLabelIndex >= 0) {
          const merged = [...nextExisting]
          merged[runningSameLabelIndex] = item
          return { ...message, activity: merged.slice(-8) }
        }
        const last = nextExisting[nextExisting.length - 1]
        const nextActivity =
          last && (last.id === item.id || (last.label === item.label && last.status === item.status))
            ? [...nextExisting.slice(0, -1), item]
            : [...nextExisting, item].slice(-8)
        return { ...message, activity: nextActivity }
      }),
    )
  }

  function submitRequirement(event?: FormEvent, customText?: string) {
    event?.preventDefault()
    const text = (customText ?? draft).trim()
    if (!text || isSubmitting) return

    // 1. 初始化咨询模式引用，由后端事件流绝对驱动 (Backend-State Driven)
    isConsultModeRef.current = false
    isClarificationFlowRef.current = false

    streamCleanupRef.current?.()
    setIsSubmitting(true)
    setIsConfirming(false)
    setSubmitError(null)
    setCurrentPlan(null)
    setCurrentTimeline([])
    setChatDraft('')
    setSseEvents([])
    
    const streamMsgId = `planpal-stream-${Date.now()}`
    
    // 2. 首页第一次输入的内容（Prompt）作为 user 气泡精准呈现在 Chat 列中，不被吞掉
    setChatMessages([
      {
        id: `user-init-${Date.now()}`,
        role: 'user',
        content: text,
      },
      {
        id: streamMsgId,
        role: 'planpal',
        content: '🔍 正在为您检索宝藏库，定制最贴近您的出行攻略与精选推荐...',
        isLoading: true,
      },
    ])
    
    setIsConfirmModalOpen(false)
    setFailedOrderIds([])
    setRequirement(text)
    
    // 3. 初始使用完全泛化无硬编码的温馨文案
    setPlanSummary('正在理解偏好与出行诉求，开始智能行程定制与灵感探索...')
    setPlanNodes([])
    setColumns((current) => current.includes('dev') ? ['chat', 'puzzle', 'dev'] : ['chat', 'puzzle'])
    setSelectedMerchantPlace(null)
    setActiveMobileTab('puzzle')
    setStage('planning')

    streamCleanupRef.current = requestPlanStream(
      {
        userId: 'U001',
        prompt: text,
      },
      {
        onEvent: (streamEvent) => {
          consumeStreamEvent(streamEvent)
          // 4. 后端状态驱动拦截：若后端带上了 consulting 标志，立刻锁定 consulting 分流
          if (streamEvent.timeline?.length) {
            isConsultModeRef.current = false
          } else if (streamEvent.intent && streamEvent.intent.isConsultingMode !== undefined) {
            isConsultModeRef.current = streamEvent.intent.isConsultingMode
          }

          const isConsult = isConsultModeRef.current && !streamEvent.timeline?.length

          if (isConsult) {
            if (streamEvent.type === 'START' || streamEvent.type === 'THOUGHT') {
              // 5. 纠正流错位漏洞：探索咨询长文攻略精准流式输出至左侧 Chat 气泡中，绝不误灌顶栏
              setChatMessages((messages) =>
                messages.map((m) =>
                  m.id === streamMsgId
                    ? { ...m, content: streamEvent.content, actionCard: streamEvent.actionCard ?? m.actionCard }
                    : m
                )
              )
            }
            return
          }

          // 标准规划模式 (Standard Planner)
          const isClarificationEvent =
            Boolean(streamEvent.planId) &&
            streamEvent.executionStatus === 'PENDING_CONFIRMATION' &&
            !streamEvent.timeline?.length &&
            ['START', 'THOUGHT', 'FINISH'].includes(streamEvent.type)

          if (isClarificationEvent) {
            isClarificationFlowRef.current = true
            setActiveMobileTab('chat')

            if (streamEvent.type === 'THOUGHT') {
              setChatMessages((messages) =>
                messages.map((m) =>
                  m.id === streamMsgId
                    ? { ...m, content: streamEvent.content, intent: streamEvent.intent }
                    : m
                )
              )
            }

            if (streamEvent.type === 'FINISH') {
              setChatMessages((messages) =>
                messages.map((m) =>
                  m.id === streamMsgId
                    ? { ...m, content: streamEvent.content, intent: streamEvent.intent }
                    : m
                )
              )
            }

            return
          }

          if (streamEvent.type === 'PLAN_NARRATIVE') {
            setChatMessages((messages) => {
              const filtered = messages.filter((m) => m.id !== `${streamMsgId}-planning`)
              return [
                ...filtered,
                {
                  id: `planpal-narrative-${Date.now()}-${streamEvent.step}`,
                  role: 'planpal',
                  content: streamEvent.content,
                },
                {
                  id: `${streamMsgId}-finishing`,
                  role: 'planpal',
                  content: '🤖 正在合并路线、检查天气并生成最终总结...',
                  isLoading: true,
                },
              ]
            })
          }
        },
        onTimeline: (response) => {
          const nextNodes = mapPlanResponseToNodes(response, [])
          setCurrentTimeline(response.timeline)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace((current) => current ?? nextNodes[0]?.place ?? null)
        },
        onFinish: (response) => {
          setChatMessages((messages) =>
            messages.map((m) => (m.id === streamMsgId ? { ...m, isLoading: false } : m))
          )

          if (isClarificationFlowRef.current) {
            setCurrentPlan(null)
            setCurrentTimeline([])
            setPlanNodes([])
            setIsSubmitting(false)
            streamCleanupRef.current = null
            return
          }

          if (!response.timeline.length) {
            setCurrentPlan(response)
            setCurrentTimeline([])
            setPlanNodes([])
            setIsSubmitting(false)
            streamCleanupRef.current = null
            return
          }

          const nextNodes = mapPlanResponseToNodes(response, [])
          setCurrentPlan(response)
          setCurrentTimeline(response.timeline)
          setConfirmHeadcount(response.intent?.headcount || 1)

          if (isConsultModeRef.current && !response.timeline.length) {
            // 8. 左侧 Chat 气泡长文不做二次覆盖，从而维持 onEvent 已经流式完成的文本与卡片
          } else {
            applyHeaderSummaryFromResponse(response)
            setPlanNodes(nextNodes)
            setSelectedMerchantPlace(nextNodes[0]?.place ?? null)
            setChatMessages((messages) => {
              const filtered = messages.filter(
                (m) =>
                  m.id !== streamMsgId &&
                  m.id !== `${streamMsgId}-planning` &&
                  m.id !== `${streamMsgId}-finishing`
              )
              return [
                ...filtered,
                {
                  id: `planpal-finish-${Date.now()}`,
                  role: 'planpal',
                  content: response.notificationText || response.summary || '行程已为您规划完成，可以直接查看右侧拼图！',
                },
              ]
            })
          }

          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          setChatMessages((messages) =>
            messages.map((m) => (m.id === streamMsgId ? { ...m, isLoading: false } : m))
          )

          const message = error.message || '规划请求失败，请稍后重试。'
          setSubmitError(message)
          setPlanSummary('处理失败，请在对话列查看详情')
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
      },
    )
  }

  function handleBuildPuzzlePlan(poiIds: string[]) {
    if (isSubmitting) return

    // 强制重置锁，切换回标准规划流渲染，避免历史咨询流残留
    isConsultModeRef.current = false
    isClarificationFlowRef.current = false

    const headcount = currentPlan?.intent?.headcount || 2
    const poiPrompt = `基于推荐的商家（商户ID: ${poiIds.join('、')}）生成行程拼图，总共 ${headcount} 个人。如果用户没有提供明确时间范围，请先用一句话追问时间，不要填入默认时间段。`

    streamCleanupRef.current?.()
    setIsSubmitting(true)
    setIsConfirming(false)
    setSubmitError(null)
    setCurrentPlan(null)
    setCurrentTimeline([])
    setChatDraft('')
    
    // 无损历史：增量追加 user 指令气泡与 planpal 加载气泡
    const buildMsgId = `planpal-build-${Date.now()}`
    setChatMessages((messages) => [
      ...messages,
      {
        id: `user-cta-${Date.now()}`,
        role: 'user',
        content: '🎨 同意并构建刚才推荐的完整方案行程',
      },
      {
        id: buildMsgId,
        role: 'planpal',
        content: '正在将您挑选的推荐商户进行闭环拼装，请稍候...',
        isLoading: true,
      },
    ])
    
    setIsConfirmModalOpen(false)
    setFailedOrderIds([])
    setDraft(poiPrompt)
    setSseEvents((events) => [
      ...events,
      {
        type: 'DIVIDER',
        content: '🎨 同意并一键合成拼图方案，正在进行闭环拼装...',
        step: 0,
      },
    ])
    setRequirement(poiPrompt)
    setPlanSummary('正在一键合成拼图方案...')
    setPlanNodes([])
    setColumns((current) => current.includes('dev') ? ['chat', 'puzzle', 'dev'] : ['chat', 'puzzle'])
    setSelectedMerchantPlace(null)
    setActiveMobileTab('puzzle')
    setStage('planning')

    streamCleanupRef.current = requestPlanStream(
      {
        userId: 'U001',
        prompt: poiPrompt,
      },
      {
        onEvent: (streamEvent) => {
          consumeStreamEvent(streamEvent)
          if (streamEvent.type === 'PLAN_NARRATIVE') {
            setChatMessages((messages) => {
              const filtered = messages.filter((m) => m.id !== `${buildMsgId}-planning`)
              return [
                ...filtered,
                {
                  id: `planpal-narrative-${Date.now()}-${streamEvent.step}`,
                  role: 'planpal',
                  content: streamEvent.content,
                },
                {
                  id: `${buildMsgId}-finishing`,
                  role: 'planpal',
                  content: '🤖 正在合并路线、检查天气并生成最终总结...',
                  isLoading: true,
                },
              ]
            })
          }
        },
        onTimeline: (response) => {
          const nextNodes = mapPlanResponseToNodes(response, [])
          setCurrentTimeline(response.timeline)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace((current) => current ?? nextNodes[0]?.place ?? null)
        },
        onFinish: (response) => {
          const nextNodes = mapPlanResponseToNodes(response, [])
          setCurrentPlan(response)
          setCurrentTimeline(response.timeline)
          setConfirmHeadcount(response.intent?.headcount || 1)
          applyHeaderSummaryFromResponse(response)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace(nextNodes[0]?.place ?? null)
          setChatMessages((messages) => {
            const filtered = messages.filter(
              (m) =>
                m.id !== buildMsgId &&
                m.id !== `${buildMsgId}-planning` &&
                m.id !== `${buildMsgId}-finishing`
            )
            return [
              ...filtered,
              {
                id: `planpal-finish-${Date.now()}`,
                role: 'planpal',
                content: response.notificationText || response.summary,
              },
            ]
          })
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          const message = error.message || '规划请求失败，请稍后重试。'
          setSubmitError(message)
          setPlanSummary('处理失败，请在对话列查看详情')
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
      },
    )
  }

  function handleBuildAdjustedPuzzlePlan(poiIds: string[], adjustmentText: string) {
    if (isSubmitting || !adjustmentText.trim()) return

    // 强制重置锁，切换回标准规划流渲染，避免历史咨询流残留
    isConsultModeRef.current = false
    isClarificationFlowRef.current = false

    const headcount = currentPlan?.intent?.headcount || 2
    const poiPrompt = `帮我把推荐的商家（商户ID: ${poiIds.join('、')}）规划到下午的行程拼图中，总共 ${headcount} 个人，请重算全部时间与交通衔接，并且特殊要求：${adjustmentText}。`

    streamCleanupRef.current?.()
    setIsSubmitting(true)
    setIsConfirming(false)
    setSubmitError(null)
    setCurrentPlan(null)
    setCurrentTimeline([])
    setChatDraft('')

    // 无损历史：增量追加 user 指微调指令气泡与 planpal 加载气泡
    const tweakMsgId = `planpal-build-${Date.now()}`
    setChatMessages((messages) => [
      ...messages,
      {
        id: `user-tweak-${Date.now()}`,
        role: 'user',
        content: `🔧 申请微调并构建行程：${adjustmentText}`,
      },
      {
        id: tweakMsgId,
        role: 'planpal',
        content: `正在根据您的微调想法（“${adjustmentText}”）合成拼图行程，请稍候...`,
        isLoading: true,
      },
    ])

    setIsConfirmModalOpen(false)
    setFailedOrderIds([])
    setDraft(poiPrompt)
    setRequirement(poiPrompt)
    setSseEvents((events) => [
      ...events,
      {
        type: 'DIVIDER',
        content: `🔧 申请微调并构建行程：${adjustmentText}`,
        step: 0,
      },
    ])
    setPlanSummary('正在合成微调拼图方案...')
    setPlanNodes([])
    setColumns((current) => current.includes('dev') ? ['chat', 'puzzle', 'dev'] : ['chat', 'puzzle'])
    setSelectedMerchantPlace(null)
    setActiveMobileTab('puzzle')
    setStage('planning')

    streamCleanupRef.current = requestPlanStream(
      {
        userId: 'U001',
        prompt: poiPrompt,
      },
      {
        onEvent: (streamEvent) => {
          consumeStreamEvent(streamEvent)
          if (streamEvent.type === 'PLAN_NARRATIVE') {
            setChatMessages((messages) => {
              const filtered = messages.filter((m) => m.id !== `${tweakMsgId}-planning`)
              return [
                ...filtered,
                {
                  id: `planpal-narrative-${Date.now()}-${streamEvent.step}`,
                  role: 'planpal',
                  content: streamEvent.content,
                },
                {
                  id: `${tweakMsgId}-finishing`,
                  role: 'planpal',
                  content: '🤖 正在合并路线、检查天气并生成最终总结...',
                  isLoading: true,
                },
              ]
            })
          }
        },
        onTimeline: (response) => {
          const nextNodes = mapPlanResponseToNodes(response, [])
          setCurrentTimeline(response.timeline)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace((current) => current ?? nextNodes[0]?.place ?? null)
        },
        onFinish: (response) => {
          const nextNodes = mapPlanResponseToNodes(response, [])
          setCurrentPlan(response)
          setCurrentTimeline(response.timeline)
          setConfirmHeadcount(response.intent?.headcount || 1)
          applyHeaderSummaryFromResponse(response)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace(nextNodes[0]?.place ?? null)
          setChatMessages((messages) => {
            const filtered = messages.filter(
              (m) =>
                m.id !== tweakMsgId &&
                m.id !== `${tweakMsgId}-planning` &&
                m.id !== `${tweakMsgId}-finishing`
            )
            return [
              ...filtered,
              {
                id: `planpal-finish-${Date.now()}`,
                role: 'planpal',
                content: response.notificationText || response.summary,
              },
            ]
          })
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          const message = error.message || '调整方案失败，请稍后重试。'
          setSubmitError(message)
          setPlanSummary('处理失败，请在对话列查看详情')
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
      },
    )
  }

  function handleReset() {
    streamCleanupRef.current?.()
    streamCleanupRef.current = null
    setStage('intro')
    setPlanSummary('')
    setSubmitError(null)
    setIsSubmitting(false)
    setIsConfirming(false)
    setCurrentPlan(null)
    setCurrentTimeline([])
    setChatDraft('')
    setChatMessages([])
    setIsConfirmModalOpen(false)
    setFailedOrderIds([])
  }

  function openConfirmModal() {
    if (!currentPlan?.planId || isSubmitting || isConfirming || stage === 'confirmed') return
    setConfirmHeadcount(currentPlan.intent?.headcount || 1)
    setIsConfirmModalOpen(true)
  }

  async function confirmCurrentPlan() {
    if (!currentPlan?.planId || isSubmitting || isConfirming || stage === 'confirmed') return

    setIsConfirming(true)
    setSubmitError(null)

    try {
      const orderedTimeline = orderedTimelineForCurrentNodes()
      const headcount = confirmHeadcount || currentPlan.intent?.headcount || 1
      
      setSseEvents(events => [
        ...events,
        {
          type: 'START',
          step: 0,
          content: '⚡ [Dev Console] 触发用户方案确认，开始执行企业级下单流程 (POST /api/v1/agent/plan/.../confirm)...'
        }
      ])

      const result = await confirmPlan(currentPlan.planId, {
        planId: currentPlan.planId,
        userId: currentPlan.userId || 'U001',
        timeline: orderedTimeline,
        headcount,
        notificationText: currentPlan.notificationText || planSummary,
      })

      const devLogs: AgentPlanStreamEvent[] = []
      
      orderedTimeline.forEach((step, idx) => {
        if (step.isTransit || !step.poiId) return
        
        const isDining = ['DINING', 'DRINKS'].includes(step.phase)
        const toolName = isDining ? 'reserveRestaurant' : 'bookTickets'
        
        devLogs.push({
          type: 'ACTION',
          step: idx + 1,
          content: `后端框架调用 [${toolName}] 工具为 “${step.poiName}” 申请锁定配额...\n入参: { poiId: "${step.poiId}", headcount: ${headcount}, targetTime: "${step.startTime || '14:00'}" }`,
        })

        const updatedStep = result.timeline?.find(s => s.poiId === step.poiId)
        const isSuccess = updatedStep && (updatedStep.bookingStatus === '已下单' || updatedStep.bookingStatus?.includes('确认') || updatedStep.bookingStatus?.includes('已'))
        
        if (isSuccess) {
          const orderId = result.executedOrders.find(id => id.includes(step.poiId) || id.includes('RES') || id.includes('TKT')) || `SUCC-${step.poiId}`
          devLogs.push({
            type: 'OBSERVATION',
            step: idx + 1,
            content: `[${toolName}] 预订成功！Sandbox 数据库事务已提交。\n返回业务凭证订单号: ${orderId}`,
          })
        } else {
          devLogs.push({
            type: 'ERROR',
            step: idx + 1,
            content: `[${toolName}] 预订失败！Sandbox 返回配额已售罄或时间段冲突。`,
          })
        }
      })

      devLogs.push({
        type: 'ACTION',
        step: 99,
        content: `后端调用汇总结算工具 [executeOrderAndNotify] 将所有子订单打包合并，提交到最终结算网关...\n订单凭证列表: ${JSON.stringify(result.executedOrders)}`,
      })

      const executed = result.status === 'DISPATCHED'
      if (executed) {
        devLogs.push({
          type: 'OBSERVATION',
          step: 99,
          content: `[executeOrderAndNotify] 结算网关批量处理成功！\n生成全局交易组号 (orderGroupId): ${result.orderGroupId}\n已自动分发交付短信及行前通知。`,
        })
        devLogs.push({
          type: 'FINISH',
          step: 100,
          content: `🎉 下单网关事务完美闭环！行程拼图节点状态已正式锁定并同步。`,
        })
      } else {
        devLogs.push({
          type: 'ERROR',
          step: 99,
          content: `[executeOrderAndNotify] 批量结算异常，交易触发反向回滚。`,
        })
      }

      setSseEvents(events => [...events, ...devLogs])

      const nextSummary = executed
        ? `已按 ${headcount} 人执行下单，订单组号 ${result.orderGroupId}。`
        : '执行下单时遇到问题，已保留当前方案，请检查后重试。'
      const nextPlan: AgentPlanResponse = {
        ...currentPlan,
        executionStatus: executed ? 'EXECUTED' : 'FAILED',
        notificationText: result.notificationText,
        orderGroupId: result.orderGroupId,
        status: executed ? 'SUCCESS' : 'FAILED',
        summary: nextSummary,
        timeline: result.timeline,
      }

      setCurrentPlan(nextPlan)
      setCurrentTimeline(result.timeline)
      setPlanSummary(nextSummary)
      setPlanNodes(mapPlanResponseToNodes(nextPlan, planNodes))
      setFailedOrderIds(result.failedOrders)
      setIsConfirmModalOpen(!executed)
      setStage(executed ? 'confirmed' : 'planning')
    } catch (error) {
      const message = error instanceof Error ? error.message : '确认方案失败，请稍后重试。'
      setSubmitError(message)
      setPlanSummary('确认方案失败，请在对话列查看详情')
    } finally {
      setIsConfirming(false)
    }
  }

  function legacyReplaceNode(nodeId: string) {
    replaceNode(nodeId)
  }

  function legacyApplyNodeRewrite(nodeId: string) {
    const text = nodeDraft.trim()
    if (!text) return

    setPlanNodes((nodes) =>
      nodes.map((node) =>
        node.id === nodeId
          ? {
              ...node,
              title: text.length > 14 ? `${text.slice(0, 14)}...` : text,
              reason: `已按“${text}”调整这个节点，同时保留整体节奏和时间顺序。`,
              status: '已按描述修改',
            }
          : node,
      ),
    )
    setEditingNodeId(null)
    setNodeDraft('')
  }

  function legacyHandleChatSend() {
    const text = chatDraft.trim()
    if (!text || isSubmitting || !currentPlan?.planId) return

    streamCleanupRef.current?.()
    setIsSubmitting(true)
    setSubmitError(null)
    setChatDraft('')
    setChatMessages((messages) => [
      ...messages,
      { id: `user-${Date.now()}`, role: 'user', content: text },
    ])

    streamCleanupRef.current = requestPlanChatStream(
      currentPlan.planId,
      {
        userId: 'U001',
        prompt: text,
      },
      {
        onEvent: (streamEvent) => {
          consumeStreamEvent(streamEvent)
        },
        onTimeline: (response) => {
          const nextNodes = mapPlanResponseToNodes(response, [])
          setCurrentTimeline(response.timeline)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace((current) => current ?? nextNodes[0]?.place ?? null)
        },
        onFinish: (response) => {
          const nextNodes = mapPlanResponseToNodes(response, [])
          setCurrentPlan(response)
          setCurrentTimeline(response.timeline)
          setConfirmHeadcount(response.intent?.headcount || 1)
          applyHeaderSummaryFromResponse(response)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace(nextNodes[0]?.place ?? null)
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          const message = error.message || '调整方案失败，请稍后重试。'
          setSubmitError(message)
          setPlanSummary('处理失败，请在对话列查看详情')
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
      }
    )
  }

  void legacyReplaceNode
  void legacyApplyNodeRewrite
  void legacyHandleChatSend
  void legacyHandleNodeDrop
  void legacyMoveNodeUp
  void legacyMoveNodeDown

  function appendPlanPalMessage(
    content: string,
    extra?: { actionCard?: ChatMessage['actionCard']; planPatch?: unknown | null },
  ) {
    setChatMessages((messages) => [
      ...messages,
      {
        id: `planpal-${Date.now()}-${messages.length}`,
        role: 'planpal',
        content,
        actionCard: extra?.actionCard ?? null,
        planPatch: extra?.planPatch ?? null,
      },
    ])
  }

  function runChatAdjustment(
    payload: AgentPlanChatRequest,
    options?: { clearDraft?: boolean; finishEditingNode?: boolean; userMessage?: string },
  ) {
    if (isSubmitting || !currentPlan?.planId) return

    const promptText = payload.prompt.trim()
    if (!promptText && !payload.patch) return

    streamCleanupRef.current?.()
    setIsSubmitting(true)
    setSubmitError(null)

    const sourceLabelMap: Record<string, string> = {
      'puzzle-move-up': '🔼 上移拼图节点，重新排列行程顺序...',
      'puzzle-move-down': '🔽 下移拼图节点，重新排列行程顺序...',
      'puzzle-drag-reorder': '🔀 拖拽拼图卡片，重新排列行程顺序...',
      'puzzle-replace': '🔄 触发“换一个”推荐商户，开始局部重新检索与规划...',
      'puzzle-rewrite': '✏️ 微调描述修改，重新生成局部行程节点...',
      'chat-inline': `💬 接收到聊天框微调意图，正在更新规划意图并重新排程...`,
    }

    let dividerContent = '🔄 接收到微调指令，正在处理行程更新...'
    const userMsg = options?.userMessage
    if (payload.source) {
      if (payload.source.startsWith('action-card:')) {
        dividerContent = `⚡ 采纳快捷建议：${userMsg || '局部调整方案'}，正在智能合成...`
      } else {
        dividerContent = sourceLabelMap[payload.source] || `🔄 行程微调演进中 (${payload.source})...`
      }
    } else if (userMsg) {
      dividerContent = `💬 微调意图：“${userMsg}”，正在重新排程...`
    }

    setSseEvents((events) => [
      ...events,
      {
        type: 'DIVIDER',
        content: dividerContent,
        step: 0,
      },
    ])

    if (options?.clearDraft) {
      setChatDraft('')
    }
    if (options?.finishEditingNode) {
      setEditingNodeId(null)
      setNodeDraft('')
    }
    const userMessage = options?.userMessage
    if (userMessage) {
      setChatMessages((messages) => [
        ...messages,
        { id: `user-${Date.now()}`, role: 'user', content: userMessage },
        { id: `planpal-loading-${Date.now()}`, role: 'planpal', content: '', isLoading: true },
      ])
    } else {
      setChatMessages((messages) => [
        ...messages,
        { id: `planpal-loading-${Date.now()}`, role: 'planpal', content: '', isLoading: true },
      ])
    }

    streamCleanupRef.current = requestPlanChatStream(
      currentPlan.planId,
      {
        ...payload,
        userId: payload.userId || 'U001',
        prompt: promptText || '请按结构化修改指令调整当前方案。',
      },
      {
        onEvent: (streamEvent) => {
          consumeStreamEvent(streamEvent)

          if (isChatOnlyFinishEvent(streamEvent)) return
        },
        onTimeline: (response) => {
          const nextNodes = mapPlanResponseToNodes(response, [])
          setCurrentTimeline(response.timeline)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace((current) => current ?? nextNodes[0]?.place ?? null)
        },
        onFinish: (response) => {
          setChatMessages((messages) => messages.filter((m) => !m.isLoading))
          if (!response.timeline.length) {
            setIsSubmitting(false)
            streamCleanupRef.current = null
            return
          }
          const nextNodes = mapPlanResponseToNodes(response, [])
          setCurrentPlan(response)
          setCurrentTimeline(response.timeline)
          setConfirmHeadcount(response.intent?.headcount || 1)
          applyHeaderSummaryFromResponse(response)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace(nextNodes[0]?.place ?? null)
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          setChatMessages((messages) => messages.filter((m) => !m.isLoading))
          const message = error.message || '调整方案失败，请稍后重试。'
          setSubmitError(message)
          setPlanSummary('处理失败，请在对话列查看详情')
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
      },
    )
  }

  function buildReorderPatch(
    movedSegmentId: string,
    anchorSegmentId: string | null,
    position: 'BEFORE' | 'AFTER' | 'START' | 'END',
  ): AgentPlanPatch {
    return {
      intent: 'MODIFY_PLAN',
      editType: 'REORDER',
      target: {
        segmentId: movedSegmentId,
        anchorSegmentId,
        position,
      },
      requirements: {
        keep: [],
        avoid: [],
        prefer: [],
        endEarlier: false,
      },
      requiresSearch: false,
    }
  }

  function businessNodesFromPlan() {
    return planNodes.filter((node) => !node.isTransit && !!node.segmentId)
  }

  function replaceNode(nodeId: string) {
    const node = planNodes.find((item) => item.id === nodeId)
    if (!node?.segmentId || node.isTransit) return

    runChatAdjustment(
      {
        userId: 'U001',
        prompt: `换掉“${node.title}”`,
        segmentId: node.segmentId,
        source: 'puzzle-replace-preview',
      },
      { userMessage: `换掉“${node.title}”` },
    )
  }

  function applyNodeRewrite(nodeId: string) {
    const text = nodeDraft.trim()
    const node = planNodes.find((item) => item.id === nodeId)
    if (!text || !node?.segmentId) return

    runChatAdjustment(
      {
        userId: 'U001',
        prompt: text,
        segmentId: node.segmentId,
        source: 'puzzle-rewrite',
      },
      {
        finishEditingNode: true,
        userMessage: `修改“${node.title}”：${text}`,
      },
    )
  }

  function handleChatSend(customText?: string) {
    const text = (customText ?? chatDraft).trim()
    if (!text) return

    if (!currentPlan?.planId) {
      // 关键信息补全阶段：将首页 prompt 与补充信息 text 融合，发送全新规划请求
      let combined = requirement
      if (combined) {
        if (!combined.endsWith('。') && !combined.endsWith('.') && !combined.endsWith('！') && !combined.endsWith('!')) {
          combined += '。'
        }
        combined += text
      } else {
        combined = text
      }
      submitRequirement(undefined, combined)
      return
    }

    runChatAdjustment(
      {
        userId: 'U001',
        prompt: text,
        source: 'chat-input',
      },
      {
        clearDraft: true,
        userMessage: text,
      },
    )
  }

  function handleNodeDrop(targetNodeId: string) {
    if (!draggingNodeId || draggingNodeId === targetNodeId) return

    const businessNodes = businessNodesFromPlan()
    const movedNode = businessNodes.find((node) => node.id === draggingNodeId)
    if (!movedNode?.segmentId) return

    const patch =
      targetNodeId === '__end__'
        ? buildReorderPatch(movedNode.segmentId, null, 'END')
        : (() => {
            const targetNode = businessNodes.find((node) => node.id === targetNodeId)
            return targetNode?.segmentId
              ? buildReorderPatch(movedNode.segmentId, targetNode.segmentId, 'BEFORE')
              : null
          })()

    if (!patch) return
    setDraggingNodeId(null)
    setDragOverNodeId(null)

    runChatAdjustment({
      userId: 'U001',
      prompt: '',
      source: 'puzzle-drag-reorder',
      patch,
    })
  }

  function moveNodeUp(nodeId: string) {
    const businessNodes = businessNodesFromPlan()
    const index = businessNodes.findIndex((node) => node.id === nodeId)
    if (index <= 0) return
    const movedNode = businessNodes[index]
    const anchorNode = businessNodes[index - 1]
    if (!movedNode?.segmentId || !anchorNode?.segmentId) return

    runChatAdjustment({
      userId: 'U001',
      prompt: '',
      source: 'puzzle-move-up',
      patch: buildReorderPatch(movedNode.segmentId, anchorNode.segmentId, 'BEFORE'),
    })
  }

  function moveNodeDown(nodeId: string) {
    const businessNodes = businessNodesFromPlan()
    const index = businessNodes.findIndex((node) => node.id === nodeId)
    if (index < 0 || index >= businessNodes.length - 1) return
    const movedNode = businessNodes[index]
    const anchorNode = businessNodes[index + 1]
    if (!movedNode?.segmentId || !anchorNode?.segmentId) return

    runChatAdjustment({
      userId: 'U001',
      prompt: '',
      source: 'puzzle-move-down',
      patch: buildReorderPatch(movedNode.segmentId, anchorNode.segmentId, 'AFTER'),
    })
  }

  function executeActionCardOption(
    _messageId: string,
    option: NonNullable<ChatMessage['actionCard']>['options'][number],
  ) {
    if (option.actionType === 'BUILD_PLAN') {
      handleBuildPuzzlePlan(option.poiIds || [])
      return
    }

    if (option.actionType === 'SELECT_PREFERENCE' || option.actionType === 'REQUEST_POI_RESEARCH') {
      runChatAdjustment(
        {
          userId: 'U001',
          prompt: option.prompt || option.label,
          source: `action-card:${option.actionType}`,
          clientActionId: option.id,
        },
        {
          userMessage: option.label,
        },
      )
      return
    }

    if (option.actionType === 'OPEN_REWRITE' && option.prompt) {
      setChatDraft(option.prompt)
      setActiveMobileTab('chat')
      return
    }

    runChatAdjustment(
      {
        userId: 'U001',
        prompt: option.prompt || '',
        patch: (option.planPatch as AgentPlanPatch | undefined) || undefined,
        segmentId: option.targetSegmentId || undefined,
        source: `action-card:${option.actionType}`,
        clientActionId: option.id,
      },
      {
        userMessage: option.label,
      },
    )
  }

  function sendStructuredPrompt(prompt: string, context?: { source?: string }) {
    runChatAdjustment(
      {
        userId: 'U001',
        prompt,
        source: context?.source || 'chat-inline',
      },
      {
        userMessage: prompt,
      },
    )
  }

  function addColumn(columnId: ColumnId) {
    setColumns((current) => (current.includes(columnId) ? current : [...current, columnId]))
    setIsColumnMenuOpen(false)
  }

  function openMerchantColumn(place: string) {
    setSelectedMerchantPlace(place)
    setColumns((current) => {
      if (current.includes('merchant')) return current
      const puzzleIndex = current.indexOf('puzzle')
      const next = [...current]
      next.splice(puzzleIndex + 1, 0, 'merchant')
      return next
    })
    setIsColumnMenuOpen(false)
    setActiveMobileTab('merchant')
  }

  function removeColumn(columnId: ColumnId) {
    if (columnId === 'puzzle') return
    setColumns((current) => current.filter((column) => column !== columnId))
  }

  function handleColumnDrop(targetColumn: ColumnId) {
    if (!draggingColumn || draggingColumn === targetColumn) return

    setColumns((current) => {
      const fromIndex = current.indexOf(draggingColumn)
      const toIndex = current.indexOf(targetColumn)
      if (fromIndex < 0 || toIndex < 0) return current

      const next = [...current]
      const [moved] = next.splice(fromIndex, 1)
      next.splice(toIndex, 0, moved)
      return next
    })
    setDraggingColumn(null)
  }

  function legacyHandleNodeDrop(targetNodeId: string) {
    if (!draggingNodeId || draggingNodeId === targetNodeId) return

    setPlanNodes((nodes) => {
      const businessNodes = nodes.filter((node) => !node.isTransit)
      const fromIndex = businessNodes.findIndex((node) => node.id === draggingNodeId)
      if (fromIndex < 0) return nodes

      const next = [...businessNodes]
      const [movedNode] = next.splice(fromIndex, 1)

      if (targetNodeId === '__end__') {
        next.push(movedNode)
      } else {
        const toIndex = next.findIndex((node) => node.id === targetNodeId)
        if (toIndex < 0) {
          next.push(movedNode)
        } else {
          next.splice(toIndex, 0, movedNode)
        }
      }

      return rebuildTimelineWithTransit(next)
    })
    setDraggingNodeId(null)
  }

  function legacyMoveNodeUp(nodeId: string) {
    setPlanNodes((nodes) => {
      const businessNodes = nodes.filter((node) => !node.isTransit)
      const index = businessNodes.findIndex((node) => node.id === nodeId)
      if (index <= 0) return nodes
      const next = [...businessNodes]
      const [moved] = next.splice(index, 1)
      next.splice(index - 1, 0, moved)
      return rebuildTimelineWithTransit(next)
    })
  }

  function legacyMoveNodeDown(nodeId: string) {
    setPlanNodes((nodes) => {
      const businessNodes = nodes.filter((node) => !node.isTransit)
      const index = businessNodes.findIndex((node) => node.id === nodeId)
      if (index < 0 || index >= businessNodes.length - 1) return nodes
      const next = [...businessNodes]
      const [moved] = next.splice(index, 1)
      next.splice(index + 1, 0, moved)
      return rebuildTimelineWithTransit(next)
    })
  }

  function allowDrop(event: DragEvent<HTMLElement>) {
    event.preventDefault()
  }

  const boardColsClass = {
    1: 'md:w-[min(1680px,calc(100%-108px))] md:grid-cols-[minmax(320px,620px)] md:justify-center',
    2: 'md:w-[min(1120px,calc(100%-108px))] md:grid-cols-[repeat(2,minmax(0,1fr))] md:justify-center',
    3: 'md:w-[min(1680px,calc(100%-108px))] md:grid-cols-[repeat(3,minmax(0,1fr))]',
    4: 'md:w-[min(1680px,calc(100%-108px))] md:grid-cols-[repeat(4,minmax(0,1fr))]',
    5: 'md:w-[min(1900px,calc(100%-108px))] md:grid-cols-[repeat(5,minmax(0,1fr))]',
  }[columns.length]

  if (stage === 'intro') {
    return (
      <IntroScreen
        draft={draft}
        examplePrompts={examplePrompts}
        isSubmitting={isSubmitting}
        onDraftChange={setDraft}
        onSubmit={submitRequirement}
        submitError={submitError}
        submitTarget={API_BASE_URL}
      />
    )
  }

  return (
    <main className="flex flex-col h-screen min-h-0 bg-animal-grid bg-animal-bg overflow-hidden">
      <PlanningHeader
        requirement={requirement}
        isConfirming={isConfirming}
        summary={planSummary}
        stage={stage}
        onConfirm={openConfirmModal}
        onReset={handleReset}
      />

      <section
        className={`grid grid-cols-1 items-stretch flex-1 min-h-0 gap-0 md:gap-3.5 mx-auto w-full px-0 pt-0 pb-0 md:px-3.5 md:pt-3.5 md:pb-[76px] overflow-y-hidden overflow-x-hidden md:overflow-x-auto ${boardColsClass}`}
      >
        {(() => {
          const columnsToRender = [...columns]
          if (!columnsToRender.includes(activeMobileTab)) {
            columnsToRender.push(activeMobileTab)
          }

          return columnsToRender.map((column) => {
            const isDesktopActive = columns.includes(column)
            const isMobileActive = activeMobileTab === column

            if (!isDesktopActive && !isMobileActive) return null

            let visibilityClass = 'hidden'
            if (isDesktopActive && isMobileActive) {
              visibilityClass = 'flex'
            } else if (isDesktopActive) {
              visibilityClass = 'hidden md:flex'
            } else if (isMobileActive) {
              visibilityClass = 'flex md:hidden'
            }

            return (
              <section
                className={`${visibilityClass} flex-col min-w-0 min-h-0 h-full animate-column-pop transition-all duration-200 ${
                  draggingColumn === column ? 'opacity-50 scale-[0.98] -translate-y-0.5' : ''
                } ${
                  dragOverColumn === column && draggingColumn !== column
                    ? 'ring-4 ring-dashed ring-[#19c8b9] bg-[#e6f9f6]/30 rounded-[26px]'
                    : 'ring-4 ring-transparent'
                }`}
                key={column}
                onDragOver={(event) => {
                  if (draggingColumn) {
                    allowDrop(event)
                  }
                }}
                onDragEnter={() => {
                  if (draggingColumn && draggingColumn !== column) {
                    setDragOverColumn(column)
                  }
                }}
                onDragLeave={(event) => {
                  if (event.currentTarget.contains(event.relatedTarget as Node)) return
                  setDragOverColumn(null)
                }}
                onDrop={() => {
                  if (draggingColumn) {
                    handleColumnDrop(column)
                    setDragOverColumn(null)
                  }
                }}
              >
                <ColumnHeader
                  column={column}
                  onDragEnd={() => {
                    setDraggingColumn(null)
                    setDragOverColumn(null)
                  }}
                  onDragStart={() => setDraggingColumn(column)}
                  onRemove={removeColumn}
                />
                <div className="flex flex-col flex-1 min-h-0 border-0 md:border-2 border-[rgba(196,184,158,0.78)] rounded-none md:rounded-[24px] bg-[#f7f3df] overflow-hidden shadow-none md:shadow-[0_4px_0_0_#d4c9b4,0_12px_28px_rgba(61,52,40,0.09)]">
                  {column === 'puzzle' && (
                    <PuzzleColumn
                      draggingNodeId={draggingNodeId}
                      dragOverNodeId={dragOverNodeId}
                      editingNodeId={editingNodeId}
                      nodeDraft={nodeDraft}
                      isGenerating={isSubmitting}
                      nodes={planNodes}
                      onApplyRewrite={applyNodeRewrite}
                      onDragEnd={() => {
                        setDraggingNodeId(null)
                        setDragOverNodeId(null)
                      }}
                      onDragStart={setDraggingNodeId}
                      onDrop={handleNodeDrop}
                      onEdit={(nodeId) => {
                        setEditingNodeId(nodeId)
                        setNodeDraft('')
                      }}
                      onMoveDown={moveNodeDown}
                      onMoveUp={moveNodeUp}
                      onOpenMerchant={openMerchantColumn}
                      onReplace={replaceNode}
                      onSetDragOverNodeId={setDragOverNodeId}
                      onSetNodeDraft={setNodeDraft}
                    />
                  )}
                  {column === 'merchant' && (
                    <MerchantColumn
                      nodes={planNodes}
                      selectedPlace={selectedMerchantPlace}
                      onSelectPlace={setSelectedMerchantPlace}
                    />
                  )}
                  {column === 'details' && <DetailsColumn nodes={planNodes} />}
                  {column === 'map' && <MapColumn nodes={planNodes} />}
                  {column === 'dev' && (
                    <DevColumn
                      plan={currentPlan}
                      nodes={planNodes}
                      events={sseEvents}
                    />
                  )}
                  {column === 'chat' && (
                    <PlanPalChatColumn
                      draft={chatDraft}
                      onExecuteActionCardOption={executeActionCardOption}
                      isDisabled={isSubmitting}
                      messages={chatMessages}
                      onDraftChange={setChatDraft}
                      onSend={handleChatSend}
                      onSendStructuredPrompt={sendStructuredPrompt}
                      onOpenMerchant={(name) => {
                        setSelectedMerchantPlace(name)
                        addColumn('merchant')
                      }}
                      onBuildPuzzlePlan={handleBuildPuzzlePlan}
                      onBuildAdjustedPuzzlePlan={handleBuildAdjustedPuzzlePlan}
                    />
                  )}
                </div>
              </section>
            )
          })
        })()}
      </section>

      <ColumnPicker
        closedColumns={closedColumns}
        containerRef={columnContainerRef}
        isOpen={isColumnMenuOpen}
        onAddColumn={addColumn}
        onToggle={() => setIsColumnMenuOpen((open) => !open)}
      />

      <footer className="fixed right-[clamp(18px,4vw,42px)] bottom-3.5 z-30 hidden md:block">
        <Button
          type="primary"
          size="large"
          className="bg-[#6fba2c]! border-[#6fba2c]! text-white! shadow-[0_5px_0_0_#5a9e1e]! disabled:cursor-not-allowed"
          disabled={!currentPlan?.planId || isSubmitting || isConfirming || stage === 'confirmed'}
          onClick={openConfirmModal}
        >
          {stage === 'confirmed' ? '已下单' : '确认方案'}
        </Button>
      </footer>

      <ConfirmOrderModal
        failedOrders={failedOrderIds}
        headcount={confirmHeadcount}
        isConfirming={isConfirming}
        notificationText={currentPlan?.notificationText || planSummary}
        open={isConfirmModalOpen}
        orderIntents={orderIntentsForCurrentTimeline()}
        timeline={orderedTimelineForCurrentNodes()}
        onClose={() => {
          if (!isConfirming) setIsConfirmModalOpen(false)
        }}
        onConfirm={confirmCurrentPlan}
        onHeadcountChange={setConfirmHeadcount}
      />

      <MobileBottomNav activeMobileTab={activeMobileTab} onTabChange={setActiveMobileTab} />
    </main>
  )
}

export default App
