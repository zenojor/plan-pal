import { useState, useRef } from 'react'
import type { FormEvent } from 'react'
import {
  requestPlanStream,
  requestPlanChatStream,
  mapPlanResponseToNodes
} from '../api/agent'
import type {
  AgentPlanResponse,
  AgentPlanStep,
  AgentPlanStreamEvent,
  AgentPlanChatRequest
} from '../api/agent'
import { DEFAULT_USER_ID } from '../config/api'
import type { ChatMessage, PlanNode, Stage, ColumnId, SelectedRouteChoice } from '../types/plan'

export function usePlanStream(dependencies: {
  basePlan: PlanNode[]
  resetPlanningColumns: () => void
  setActiveMobileTab: (tab: ColumnId) => void
  chatDraft: string
  setChatDraft: React.Dispatch<React.SetStateAction<string>>
  chatMessages: ChatMessage[]
  setChatMessages: React.Dispatch<React.SetStateAction<ChatMessage[]>>
  setConfirmHeadcount: React.Dispatch<React.SetStateAction<number>>
  setIsConfirmModalOpen: React.Dispatch<React.SetStateAction<boolean>>
  setFailedOrderIds: React.Dispatch<React.SetStateAction<string[]>>
  setSelectedMerchantPlace: React.Dispatch<React.SetStateAction<string | null>>
  setSelectedRouteChoices: React.Dispatch<React.SetStateAction<Record<string, SelectedRouteChoice>>>
  setEditingNodeId: React.Dispatch<React.SetStateAction<string | null>>
  setNodeDraft: React.Dispatch<React.SetStateAction<string>>
}) {
  const {
    basePlan,
    resetPlanningColumns,
    setActiveMobileTab,
    chatDraft,
    setChatDraft,
    setChatMessages,
    setConfirmHeadcount,
    setIsConfirmModalOpen,
    setFailedOrderIds,
    setSelectedMerchantPlace,
    setSelectedRouteChoices,
    setEditingNodeId,
    setNodeDraft,
  } = dependencies

  const [stage, setStage] = useState<Stage>('intro')
  const [requirement, setRequirement] = useState('')
  const [planSummary, setPlanSummary] = useState('')
  const [draft, setDraft] = useState('')
  const [planNodes, setPlanNodes] = useState<PlanNode[]>(basePlan)
  const [currentPlan, setCurrentPlan] = useState<AgentPlanResponse | null>(null)
  const [currentTimeline, setCurrentTimeline] = useState<AgentPlanStep[]>([])
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [sseEvents, setSseEvents] = useState<AgentPlanStreamEvent[]>([])

  const streamCleanupRef = useRef<(() => void) | null>(null)
  const isConsultModeRef = useRef(false)
  const isClarificationFlowRef = useRef(false)
  const activityMessageIdRef = useRef<string | null>(null)

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
      !streamEvent.actionCard &&
      (streamEvent.executionStatus === 'CHAT_ONLY' || !hasTimeline(streamEvent))
    )
  }

  function isDecisionRequiredFinishEvent(streamEvent: AgentPlanStreamEvent | null | undefined) {
    return Boolean(streamEvent?.type === 'FINISH' && streamEvent.actionCard)
  }

  function isExecutablePlan(response: AgentPlanResponse) {
    if (!response?.planId || response.executionStatus === 'CHAT_ONLY') return false
    return Boolean(
      response.timeline.length ||
        response.executionStatus === 'PENDING_CONFIRMATION' ||
        response.executionStatus === 'OPTIONS_READY'
    )
  }

  function consumeStreamEvent(streamEvent: AgentPlanStreamEvent, loadingMessageId?: string) {
    appendOrUpdateSseEvent(streamEvent)
    const headerSummary = headerSummaryFromStreamEvent(streamEvent)
    if (headerSummary) {
      setPlanSummary(headerSummary)
    }

    if (isDecisionRequiredFinishEvent(streamEvent) || isChatOnlyFinishEvent(streamEvent)) {
      replaceLatestLoadingPlanPalMessage(streamEvent.content || streamEvent.actionCard?.description || '请在对话列继续操作。', {
        actionCard: streamEvent.actionCard ?? null,
        planPatch: streamEvent.planPatch ?? null,
      }, loadingMessageId)
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
      'consult.respond': '整理出行方向',
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
                  content: '本轮处理细节',
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
          content: '本轮处理细节',
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

  function replaceLatestLoadingPlanPalMessage(
    content: string,
    extra?: { actionCard?: ChatMessage['actionCard']; planPatch?: unknown | null },
    targetMessageId?: string,
  ) {
    setChatMessages((messages) => {
      const nextMessage: ChatMessage = {
        id: `planpal-${Date.now()}-${messages.length}`,
        role: 'planpal',
        content,
        actionCard: extra?.actionCard ?? null,
        planPatch: extra?.planPatch ?? null,
        isStreaming: true,
      }

      if (targetMessageId) {
        const targetIndex = messages.findIndex((message) => message.id === targetMessageId)
        if (targetIndex >= 0) {
          const message = messages[targetIndex]
          return [
            ...messages.slice(0, targetIndex),
            {
              ...message,
              content,
              actionCard: extra?.actionCard ?? message.actionCard ?? null,
              planPatch: extra?.planPatch ?? message.planPatch ?? null,
              isLoading: false,
              isStreaming: true,
            },
            ...messages.slice(targetIndex + 1),
          ]
        }
      }

      for (let index = messages.length - 1; index >= 0; index--) {
        const message = messages[index]
        if (message.role === 'planpal' && message.isLoading && !message.activity?.length) {
          return [
            ...messages.slice(0, index),
            {
              ...message,
              content,
              actionCard: extra?.actionCard ?? message.actionCard ?? null,
              planPatch: extra?.planPatch ?? message.planPatch ?? null,
              isLoading: false,
              isStreaming: true,
            },
            ...messages.slice(index + 1),
          ]
        }
      }

      return [...messages, nextMessage]
    })
  }

  function submitRequirement(event?: FormEvent, customText?: string) {
    event?.preventDefault()
    const text = (customText ?? draft).trim()
    if (!text || isSubmitting) return

    isConsultModeRef.current = false
    isClarificationFlowRef.current = false

    streamCleanupRef.current?.()
    setIsSubmitting(true)
    setSubmitError(null)
    setCurrentPlan(null)
    setCurrentTimeline([])
    setChatDraft('')
    setSseEvents([])
    
    const streamMsgId = `planpal-stream-${Date.now()}`
    
    setChatMessages([
      {
        id: `user-init-${Date.now()}`,
        role: 'user',
        content: text,
      },
      {
        id: streamMsgId,
        role: 'planpal',
        content: '正在理解需求',
        isLoading: true,
      },
    ])
    
    setIsConfirmModalOpen(false)
    setFailedOrderIds([])
    setRequirement(text)
    
    setPlanSummary('正在理解偏好与出行诉求，开始智能行程定制与灵感探索...')
    setPlanNodes([])
    resetPlanningColumns()
    setSelectedMerchantPlace(null)
    setStage('planning')

    streamCleanupRef.current = requestPlanStream(
      {
        userId: DEFAULT_USER_ID,
        prompt: text,
      },
      {
        onEvent: (streamEvent) => {
          consumeStreamEvent(streamEvent)
          if (streamEvent.timeline?.length) {
            isConsultModeRef.current = false
          } else if (streamEvent.intent && streamEvent.intent.isConsultingMode !== undefined) {
            isConsultModeRef.current = streamEvent.intent.isConsultingMode
          }

          const isConsult = isConsultModeRef.current && !streamEvent.timeline?.length

          if (isConsult) {
            if (streamEvent.type === 'START' || streamEvent.type === 'THOUGHT') {
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
                    ? { ...m, content: streamEvent.content, intent: streamEvent.intent, actionCard: streamEvent.actionCard ?? m.actionCard }
                    : m
                )
              )
            }

            if (streamEvent.type === 'FINISH') {
              setChatMessages((messages) =>
                messages.map((m) =>
                  m.id === streamMsgId
                    ? { ...m, content: streamEvent.content, intent: streamEvent.intent, actionCard: streamEvent.actionCard ?? m.actionCard, isStreaming: true }
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
                  isStreaming: true,
                },
                {
                  id: `${streamMsgId}-finishing`,
                  role: 'planpal',
                  content: '正在合并方案',
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
        onFinish: (response, event) => {
          setChatMessages((messages) =>
            messages.map((m) => (m.id === streamMsgId ? { ...m, isLoading: false, isStreaming: true } : m))
          )

          if (isClarificationFlowRef.current) {
            if (isExecutablePlan(response)) {
              setCurrentPlan(response)
            } else {
              setCurrentPlan(null)
            }
            setCurrentTimeline([])
            setPlanNodes([])
            setIsSubmitting(false)
            streamCleanupRef.current = null
            return
          }

          if (!response.timeline.length) {
            if (isExecutablePlan(response)) {
              setCurrentPlan(response)
            }
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

          if (isDecisionRequiredFinishEvent(event)) {
            applyHeaderSummaryFromResponse(response)
            setPlanNodes(nextNodes)
            setSelectedMerchantPlace(nextNodes[0]?.place ?? null)
            setChatMessages((messages) =>
              messages.map((m) =>
                m.id === streamMsgId
                  ? {
                      ...m,
                      content: event?.content || response.notificationText || response.summary,
                      actionCard: event?.actionCard ?? m.actionCard,
                      isLoading: false,
                      isStreaming: true,
                    }
                  : m
              )
            )
          } else if (isConsultModeRef.current && !response.timeline.length) {
            // No action needed
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
                  isStreaming: true,
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

  function handleBuildPuzzlePlanInternal(poiIds: string[], adjustmentText?: string) {
    if (isSubmitting) return
    if (adjustmentText !== undefined && !adjustmentText.trim()) return

    isConsultModeRef.current = false
    isClarificationFlowRef.current = false

    const headcount = currentPlan?.intent?.headcount || '待确认'
    const isAdjustment = adjustmentText !== undefined
    
    const poiPrompt = isAdjustment
      ? `[BUILD_SELECTED_PLAN] 原始需求：${requirement || '用户选择了一条推荐路线'}。基于推荐的商家（商户ID: ${poiIds.join('、')}）生成行程拼图，总共 ${headcount} 个人，并且特殊要求：${adjustmentText}。请保留原始需求中的时间、同行人、距离和节奏约束；如果原始需求没有明确时间范围，再用一句话追问时间，不要填入默认时间段。`
      : `[BUILD_SELECTED_PLAN] 原始需求：${requirement || '用户选择了一条推荐路线'}。基于推荐的商家（商户ID: ${poiIds.join('、')}）生成行程拼图，总共 ${headcount} 个人。请保留原始需求中的时间、同行人、距离和节奏约束；如果原始需求没有明确时间范围，再用一句话追问时间，不要填入默认时间段。`

    streamCleanupRef.current?.()
    setIsSubmitting(true)
    setSubmitError(null)
    setCurrentPlan(null)
    setCurrentTimeline([])
    setChatDraft('')

    const buildMsgId = `planpal-build-${Date.now()}`
    setChatMessages((messages) => [
      ...messages,
      isAdjustment
        ? {
            id: `user-tweak-${Date.now()}`,
            role: 'user',
            content: `🔧 申请微调并构建行程：${adjustmentText}`,
          }
        : {
            id: `user-cta-${Date.now()}`,
            role: 'user',
            content: '🎨 同意并构建刚才推荐的完整方案行程',
          },
      {
        id: buildMsgId,
        role: 'planpal',
        content: isAdjustment ? '正在合成微调方案' : '正在构建拼图方案',
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
        content: isAdjustment
          ? `🔧 申请微调并构建行程：${adjustmentText}`
          : '🎨 同意并一键合成拼图方案，正在进行闭环拼装...',
        step: 0,
      },
    ])
    setPlanSummary(isAdjustment ? '正在合成微调拼图方案...' : '正在一键合成拼图方案...')
    setPlanNodes([])
    resetPlanningColumns()
    setSelectedMerchantPlace(null)
    setStage('planning')

    streamCleanupRef.current = requestPlanStream(
      {
        userId: DEFAULT_USER_ID,
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
                  isStreaming: true,
                },
                {
                  id: `${buildMsgId}-finishing`,
                  role: 'planpal',
                  content: '正在合并方案',
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
                isStreaming: true,
              },
            ]
          })
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          const message = error.message || (isAdjustment ? '调整方案失败，请稍后重试。' : '规划请求失败，请稍后重试。')
          setSubmitError(message)
          setPlanSummary('处理失败，请在对话列查看详情')
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
      },
    )
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
    const loadingMessageId = `planpal-loading-${Date.now()}`
    if (userMessage) {
      setChatMessages((messages) => [
        ...messages,
        { id: `user-${Date.now()}`, role: 'user', content: userMessage },
        { id: loadingMessageId, role: 'planpal', content: '', isLoading: true },
      ])
    } else {
      setChatMessages((messages) => [
        ...messages,
        { id: loadingMessageId, role: 'planpal', content: '', isLoading: true },
      ])
    }

    streamCleanupRef.current = requestPlanChatStream(
      currentPlan.planId,
      {
        ...payload,
        userId: payload.userId || DEFAULT_USER_ID,
        prompt: promptText || '请按结构化修改指令调整当前方案。',
      },
      {
        onEvent: (streamEvent) => {
          consumeStreamEvent(streamEvent, loadingMessageId)
        },
        onTimeline: (response) => {
          const nextNodes = mapPlanResponseToNodes(response, [])
          setCurrentTimeline(response.timeline)
          setPlanNodes(nextNodes)
          setSelectedMerchantPlace((current) => current ?? nextNodes[0]?.place ?? null)
        },
        onFinish: (response, event) => {
          const isChatOnly = event ? isChatOnlyFinishEvent(event) : false
          const isDecisionRequired = isDecisionRequiredFinishEvent(event)
          if (isChatOnly || isDecisionRequired) {
            setChatMessages((messages) => messages.filter((m) => m.id !== loadingMessageId || !m.isLoading))
          } else {
            setChatMessages((messages) => {
              const filtered = messages.filter((m) => m.id !== loadingMessageId)
              return [
                ...filtered,
                {
                  id: `planpal-finish-${Date.now()}`,
                  role: 'planpal',
                  content: response.notificationText || response.summary || '行程已更新。',
                  isStreaming: true,
                },
              ]
            })
          }
          if (!response.timeline.length) {
            if (response.planId) {
              setCurrentPlan((previous) => {
                const base = previous && previous.planId === response.planId ? previous : null
                return {
                  ...(base || response),
                  ...response,
                  timeline: base?.timeline || response.timeline,
                  notificationText: response.notificationText || base?.notificationText || response.summary,
                  summary: response.summary || response.notificationText || base?.summary || '',
                }
              })
            }
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

  function handleReset() {
    streamCleanupRef.current?.()
    streamCleanupRef.current = null
    setStage('intro')
    setPlanSummary('')
    setSubmitError(null)
    setIsSubmitting(false)
    setCurrentPlan(null)
    setCurrentTimeline([])
    setChatDraft('')
    setChatMessages([])
    setIsConfirmModalOpen(false)
    setFailedOrderIds([])
    setSelectedRouteChoices({})
  }

  function handleChatSend(customText?: string) {
    const text = (customText ?? chatDraft).trim()
    if (!text) return

    if (!currentPlan?.planId || !isExecutablePlan(currentPlan)) {
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
        userId: DEFAULT_USER_ID,
        prompt: text,
        source: 'chat-input',
      },
      {
        clearDraft: true,
        userMessage: text,
      },
    )
  }

  return {
    stage,
    setStage,
    requirement,
    setRequirement,
    planSummary,
    setPlanSummary,
    draft,
    setDraft,
    planNodes,
    setPlanNodes,
    currentPlan,
    setCurrentPlan,
    currentTimeline,
    setCurrentTimeline,
    isSubmitting,
    setIsSubmitting,
    submitError,
    setSubmitError,
    sseEvents,
    setSseEvents,
    streamCleanupRef,
    isConsultModeRef,
    isClarificationFlowRef,
    activityMessageIdRef,
    submitRequirement,
    handleBuildPuzzlePlanInternal,
    runChatAdjustment,
    handleReset,
    handleChatSend,
  }
}
