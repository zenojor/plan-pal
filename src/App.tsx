import { useEffect, useRef, useState } from 'react'
import type { DragEvent, FormEvent } from 'react'
import { Button } from 'animal-island-ui'
import {
  confirmPlan,
  mapPlanResponseToNodes,
  requestPlanStream,
  requestPlanChatStream,
} from './api/agent'
import type { AgentOrderIntent, AgentPlanResponse, AgentPlanStep } from './api/agent'
import { ColumnHeader } from './components/ColumnHeader'
import { ColumnPicker } from './components/ColumnPicker'
import { ConfirmOrderModal } from './components/ConfirmOrderModal'
import { DetailsColumn } from './components/DetailsColumn'
import { IntroScreen } from './components/IntroScreen'
import { MapColumn } from './components/MapColumn'
import { MerchantColumn } from './components/MerchantColumn'
import { MobileBottomNav } from './components/MobileBottomNav'
import { PlanPalChatColumn } from './components/PlanPalChatColumn'
import { PlanningHeader } from './components/PlanningHeader'
import { PuzzleColumn } from './components/PuzzleColumn'
import { API_BASE_URL } from './config/api'
import {
  alternatives,
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
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([])
  const [isConfirmModalOpen, setIsConfirmModalOpen] = useState(false)
  const [confirmHeadcount, setConfirmHeadcount] = useState(1)
  const [failedOrderIds, setFailedOrderIds] = useState<string[]>([])
  const columnContainerRef = useRef<HTMLDivElement>(null)
  const streamCleanupRef = useRef<(() => void) | null>(null)
  const isConsultModeRef = useRef(false)

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

  function submitRequirement(event?: FormEvent) {
    event?.preventDefault()
    const text = draft.trim()
    if (!text || isSubmitting) return

    // 1. 初始化咨询模式引用，由后端事件流绝对驱动 (Backend-State Driven)
    isConsultModeRef.current = false

    streamCleanupRef.current?.()
    setIsSubmitting(true)
    setIsConfirming(false)
    setSubmitError(null)
    setCurrentPlan(null)
    setCurrentTimeline([])
    setChatDraft('')
    
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
      },
    ])
    
    setIsConfirmModalOpen(false)
    setFailedOrderIds([])
    setRequirement(text)
    
    // 3. 初始使用完全泛化无硬编码的温馨文案
    setPlanSummary('正在理解偏好与出行诉求，开始智能行程定制与灵感探索...')
    setPlanNodes([])
    setColumns(['chat', 'puzzle'])
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
          // 4. 后端状态驱动拦截：若后端带上了 consulting 标志，立刻锁定 consulting 分流
          if (streamEvent.intent && streamEvent.intent.isConsultingMode !== undefined) {
            isConsultModeRef.current = streamEvent.intent.isConsultingMode
          }

          const isConsult = isConsultModeRef.current

          if (isConsult) {
            if (streamEvent.type === 'START' || streamEvent.type === 'THOUGHT') {
              // 5. 纠正流错位漏洞：探索咨询长文攻略精准流式输出至左侧 Chat 气泡中，绝不误灌顶栏
              setChatMessages((messages) =>
                messages.map((m) =>
                  m.id === streamMsgId
                    ? { ...m, content: streamEvent.content }
                    : m
                )
              )

              // 6. 零硬编码泛化：根据 sceneType 动态渲染概括，自适应各种场景
              const sceneName =
                streamEvent.intent?.sceneType === 'SOCIAL'
                  ? '社交聚会'
                  : streamEvent.intent?.sceneType === 'FAMILY'
                  ? '家庭亲子'
                  : streamEvent.intent?.sceneType === 'SOLO'
                  ? '个人出行'
                  : streamEvent.intent?.sceneType === 'DATE'
                  ? '温馨约会'
                  : '智能出行'
              setPlanSummary(`已为您开启【${sceneName}】灵感定制与深度攻略探索...`)
            }
            return
          }

          // 标准规划模式 (Standard Planner)
          if (streamEvent.type !== 'OBSERVATION') {
            setPlanSummary(streamEvent.content)
          }
          if (streamEvent.type === 'INTENT') {
            setChatMessages((messages) => {
              const filtered = messages.filter((m) => m.id !== streamMsgId)
              return [
                ...filtered,
                {
                  id: `planpal-${Date.now()}-${streamEvent.step}`,
                  role: 'planpal',
                  content: streamEvent.content,
                },
              ]
            })
          }
          if (streamEvent.type === 'PLAN_STEP') {
            setChatMessages((messages) => [
              ...messages,
              {
                id: `planpal-step-${Date.now()}-${streamEvent.step}`,
                role: 'planpal',
                content: streamEvent.content.replace('已确认草案拼图：', '我已经放入一块拼图：'),
              },
            ])
          }
        },
        onTimeline: (response) => {
          if (isConsultModeRef.current) return
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

          if (isConsultModeRef.current) {
            // 7. 纠正流错位：顶栏展示精简温馨的 shortSummary (动态泛化不穿帮)
            setPlanSummary(response.summary || '已为您精选贴切的灵感出行建议！')
            // 8. 左侧 Chat 气泡长文不做二次覆盖，从而完美维持 onEvent 已经打字流式完成的富文本与 POI 卡片
          } else {
            setPlanSummary(response.summary)
            setPlanNodes(nextNodes)
            setSelectedMerchantPlace(nextNodes[0]?.place ?? null)
            setChatMessages((messages) => messages.filter((m) => m.id !== streamMsgId))
          }

          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          const message = error.message || '规划请求失败，请稍后重试。'
          setSubmitError(message)
          setPlanSummary(message)
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

    const poiPrompt = `帮我把推荐的商家（商户ID: ${poiIds.join('、')}）规划到下午 14:00 到 18:00 的行程拼图中，请重算全部时间与交通衔接。`

    streamCleanupRef.current?.()
    setIsSubmitting(true)
    setIsConfirming(false)
    setSubmitError(null)
    setCurrentPlan(null)
    setCurrentTimeline([])
    setChatDraft('')
    
    // 无损历史：增量追加 user 指令气泡与 planpal 加载气泡
    setChatMessages((messages) => [
      ...messages,
      {
        id: `user-cta-${Date.now()}`,
        role: 'user',
        content: '🎨 同意并构建刚才推荐的完整方案行程',
      },
      {
        id: `planpal-build-${Date.now()}`,
        role: 'planpal',
        content: '正在将您挑选的推荐商户进行闭环拼装，请稍候...',
      },
    ])
    
    setIsConfirmModalOpen(false)
    setFailedOrderIds([])
    setDraft(poiPrompt)
    setRequirement(poiPrompt)
    setPlanSummary('正在一键合成拼图方案...')
    setPlanNodes([])
    setColumns(['chat', 'puzzle'])
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
          if (streamEvent.type !== 'OBSERVATION') {
            setPlanSummary(streamEvent.content)
          }
          if (streamEvent.type === 'INTENT') {
            setChatMessages((messages) => [
              ...messages,
              {
                id: `planpal-${Date.now()}-${streamEvent.step}`,
                role: 'planpal',
                content: streamEvent.content,
              },
            ])
          }
          if (streamEvent.type === 'PLAN_STEP') {
            setChatMessages((messages) => [
              ...messages,
              {
                id: `planpal-step-${Date.now()}-${streamEvent.step}`,
                role: 'planpal',
                content: streamEvent.content.replace('已确认草案拼图：', '我已经放入一块拼图：'),
              },
            ])
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
          setPlanSummary(response.summary)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace(nextNodes[0]?.place ?? null)
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          const message = error.message || '规划请求失败，请稍后重试。'
          setSubmitError(message)
          setPlanSummary(message)
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

    const poiPrompt = `帮我把推荐的商家（商户ID: ${poiIds.join('、')}）规划到下午的行程拼图中，请重算全部时间与交通衔接，并且特殊要求：${adjustmentText}。`

    streamCleanupRef.current?.()
    setIsSubmitting(true)
    setIsConfirming(false)
    setSubmitError(null)
    setCurrentPlan(null)
    setCurrentTimeline([])
    setChatDraft('')

    // 无损历史：增量追加 user 指微调指令气泡与 planpal 加载气泡
    setChatMessages((messages) => [
      ...messages,
      {
        id: `user-tweak-${Date.now()}`,
        role: 'user',
        content: `🔧 申请微调并构建行程：${adjustmentText}`,
      },
      {
        id: `planpal-build-${Date.now()}`,
        role: 'planpal',
        content: `正在根据您的微调想法（“${adjustmentText}”）合成拼图行程，请稍候...`,
      },
    ])

    setIsConfirmModalOpen(false)
    setFailedOrderIds([])
    setDraft(poiPrompt)
    setRequirement(poiPrompt)
    setPlanSummary('正在合成微调拼图方案...')
    setPlanNodes([])
    setColumns(['chat', 'puzzle'])
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
          if (streamEvent.type !== 'OBSERVATION') {
            setPlanSummary(streamEvent.content)
          }
          if (streamEvent.type === 'INTENT') {
            setChatMessages((messages) => [
              ...messages,
              {
                id: `planpal-${Date.now()}-${streamEvent.step}`,
                role: 'planpal',
                content: streamEvent.content,
              },
            ])
          }
          if (streamEvent.type === 'PLAN_STEP') {
            setChatMessages((messages) => [
              ...messages,
              {
                id: `planpal-step-${Date.now()}-${streamEvent.step}`,
                role: 'planpal',
                content: streamEvent.content.replace('已确认草案拼图：', '我已经放入一块拼图：'),
              },
            ])
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
          setPlanSummary(response.summary)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace(nextNodes[0]?.place ?? null)
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          const message = error.message || '调整方案失败，请稍后重试。'
          setSubmitError(message)
          setPlanSummary(message)
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
      const result = await confirmPlan(currentPlan.planId, {
        planId: currentPlan.planId,
        userId: currentPlan.userId || 'U001',
        timeline: orderedTimeline,
        headcount,
        notificationText: currentPlan.notificationText || planSummary,
      })
      const executed = result.status === 'DISPATCHED'
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
      setPlanSummary(message)
    } finally {
      setIsConfirming(false)
    }
  }

  function replaceNode(nodeId: string) {
    const options = alternatives[nodeId]
    if (!options?.length) return

    setPlanNodes((nodes) =>
      nodes.map((node) => {
        if (node.id !== nodeId) return node
        const currentIndex = options.findIndex((item) => item.title === node.title)
        return options[(currentIndex + 1) % options.length]
      }),
    )
  }

  function applyNodeRewrite(nodeId: string) {
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

  function handleChatSend() {
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

    // 记录当前 timeline 中已有的 POI 名称，用于跳过重复输出
    const existingPoiNames = new Set(
      (currentTimeline || [])
        .filter((s) => !s.isTransit)
        .map((s) => s.poiName)
    )

    streamCleanupRef.current = requestPlanChatStream(
      currentPlan.planId,
      {
        userId: 'U001',
        prompt: text,
      },
      {
        onEvent: (streamEvent) => {
          if (streamEvent.type !== 'OBSERVATION') {
            setPlanSummary(streamEvent.content)
          }
          if (streamEvent.type === 'INTENT') {
            setChatMessages((messages) => [
              ...messages,
              {
                id: `planpal-${Date.now()}-${streamEvent.step}`,
                role: 'planpal',
                content: streamEvent.content,
              },
            ])
          }
          if (streamEvent.type === 'PLAN_STEP') {
            // 提取 POI 名称，跳过已有拼图的重复播报
            const poiMatch = streamEvent.content.match(/[：:](.+)$/)
            const poiName = poiMatch?.[1]?.trim()
            if (poiName && existingPoiNames.has(poiName)) return
            setChatMessages((messages) => [
              ...messages,
              {
                id: `planpal-${Date.now()}-${streamEvent.step}`,
                role: 'planpal',
                content: streamEvent.content.replace('已确认草案拼图：', '新增拼图：'),
              },
            ])
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
          setPlanSummary(response.summary)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace(nextNodes[0]?.place ?? null)
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          const message = error.message || '调整方案失败，请稍后重试。'
          setSubmitError(message)
          setPlanSummary(message)
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
      }
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

  function handleNodeDrop(targetNodeId: string) {
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

  function moveNodeUp(nodeId: string) {
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

  function moveNodeDown(nodeId: string) {
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
                  {column === 'chat' && (
                    <PlanPalChatColumn
                      draft={chatDraft}
                      isDisabled={isSubmitting}
                      messages={chatMessages}
                      onDraftChange={setChatDraft}
                      onSend={handleChatSend}
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
          className="bg-[#6fba2c]! border-[#6fba2c]! text-white! shadow-[0_5px_0_0_#5a9e1e]!"
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
