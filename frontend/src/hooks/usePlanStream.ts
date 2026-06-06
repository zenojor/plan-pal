import { useRef, useState } from 'react'
import type { Dispatch, FormEvent, SetStateAction } from 'react'
import {
  requestPlanStream,
  requestPlanChatStream,
  mapPlanResponseToNodes,
} from '../api/agent'
import type {
  AgentPlanResponse,
  AgentPlanStep,
  AgentPlanStreamEvent,
  AgentPlanChatRequest,
} from '../api/agent'
import { DEFAULT_USER_ID } from '../config/api'
import type {
  ChatMessage,
  PlanNode,
  Stage,
  ColumnId,
  SelectedRouteChoice,
  PlanVariantSummary,
} from '../types/plan'

type UsePlanStreamDeps = {
  basePlan: PlanNode[]
  resetPlanningColumns: () => void
  setActiveMobileTab: (tab: ColumnId) => void
  chatDraft: string
  setChatDraft: Dispatch<SetStateAction<string>>
  chatMessages: ChatMessage[]
  setChatMessages: Dispatch<SetStateAction<ChatMessage[]>>
  setConfirmHeadcount: Dispatch<SetStateAction<number>>
  setIsConfirmModalOpen: Dispatch<SetStateAction<boolean>>
  setFailedOrderIds: Dispatch<SetStateAction<string[]>>
  setSelectedMerchantPlace: Dispatch<SetStateAction<string | null>>
  setSelectedRouteChoices: Dispatch<SetStateAction<Record<string, SelectedRouteChoice>>>
  setEditingNodeId: Dispatch<SetStateAction<string | null>>
  setNodeDraft: Dispatch<SetStateAction<string>>
  onSelectPlanVariant?: (planId: string) => void
}

function safeSummary(response: AgentPlanResponse) {
  return response.summary?.trim() || response.notificationText?.trim() || 'Plan updated'
}

function isDecisionOnlyResponse(response: AgentPlanResponse, event?: AgentPlanStreamEvent) {
  return (
    response.executionStatus === 'OPTIONS_READY' ||
    event?.executionStatus === 'OPTIONS_READY' ||
    response.status === 'OPTIONS_READY' ||
    event?.actionCard?.cardKind === 'PLAN_CHOICE'
  )
}

export function usePlanStream(dependencies: UsePlanStreamDeps) {
  const {
    basePlan,
    resetPlanningColumns,
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
    onSelectPlanVariant,
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
  const planVariantsRef = useRef<Record<string, AgentPlanResponse>>({})
  const isConsultModeRef = useRef(false)
  const isClarificationFlowRef = useRef(false)
  const activityMessageIdRef = useRef<string | null>(null)

  function rememberPlanVariants(response: AgentPlanResponse) {
    const variants = response.variants || []
    planVariantsRef.current = {
      ...planVariantsRef.current,
      [response.planId]: response,
      ...Object.fromEntries(variants.map((variant) => [variant.planId, variant])),
    }
  }

  function variantSummariesFromResponse(response: AgentPlanResponse): PlanVariantSummary[] {
    return (response.variants || []).map((variant) => ({
      planId: variant.planId,
      summary: variant.summary || variant.notificationText || '',
      notificationText: variant.notificationText || variant.summary || '',
      stepCount: variant.timeline?.length || 0,
      executionStatus: variant.executionStatus || null,
    }))
  }

  function applyPlanResponse(response: AgentPlanResponse, keepSelectedMerchant = false) {
    const nextNodes = mapPlanResponseToNodes(response, [])
    setCurrentPlan(response)
    setCurrentTimeline(response.timeline || [])
    setPlanNodes(nextNodes)
    setConfirmHeadcount(response.intent?.headcount || 1)
    if (!keepSelectedMerchant) {
      setSelectedMerchantPlace(nextNodes[0]?.place ?? null)
    }
    setPlanSummary(safeSummary(response))
    return nextNodes
  }

  function updateLoadingMessage(
    loadingMessageId: string,
    content: string,
    extra?: { actionCard?: ChatMessage['actionCard']; planVariants?: PlanVariantSummary[] },
  ) {
    setChatMessages((messages) =>
      messages.map((message) =>
        message.id === loadingMessageId
          ? {
              ...message,
              content,
              actionCard: extra?.actionCard ?? message.actionCard ?? null,
              planVariants: extra?.planVariants ?? message.planVariants ?? null,
              isLoading: false,
              isStreaming: true,
            }
          : message,
      ),
    )
  }

  function selectPlanVariant(planId: string) {
    const variant = planVariantsRef.current[planId] || currentPlan?.variants?.find((item) => item.planId === planId)
    if (!variant) return
    applyPlanResponse(variant)
    onSelectPlanVariant?.(planId)
  }

  function submitRequirement(event?: FormEvent, customText?: string) {
    event?.preventDefault()
    const text = (customText ?? draft).trim()
    if (!text || isSubmitting) return

    streamCleanupRef.current?.()
    streamCleanupRef.current = null

    setIsSubmitting(true)
    setSubmitError(null)
    planVariantsRef.current = {}
    setCurrentPlan(null)
    setCurrentTimeline([])
    setPlanNodes([])
    setChatDraft('')
    setRequirement(text)
    setPlanSummary('Building plan...')
    setStage('planning')
    setIsConfirmModalOpen(false)
    setFailedOrderIds([])
    setSelectedMerchantPlace(null)
    resetPlanningColumns()

    const loadingMessageId = `planpal-stream-${Date.now()}`
    setChatMessages([
      { id: `user-init-${Date.now()}`, role: 'user', content: text },
      { id: loadingMessageId, role: 'planpal', content: 'Building plan...', isLoading: true },
    ])

    streamCleanupRef.current = requestPlanStream(
      { userId: DEFAULT_USER_ID, prompt: text },
      {
        onEvent: (event) => {
          setSseEvents((prev) => [...prev, event])
          if (event.intent?.isConsultingMode !== undefined) {
            isConsultModeRef.current = event.intent.isConsultingMode
          }
        },
        onTimeline: (response) => {
          const nodes = mapPlanResponseToNodes(response, [])
          setCurrentTimeline(response.timeline || [])
          setPlanNodes(nodes)
          setSelectedMerchantPlace((current) => current ?? nodes[0]?.place ?? null)
        },
        onFinish: (response, event) => {
          rememberPlanVariants(response)
          const planVariants = variantSummariesFromResponse(response)
          const decisionOnly = isDecisionOnlyResponse(response, event)
          setCurrentPlan(response)
          setCurrentTimeline(decisionOnly ? [] : response.timeline || [])
          setConfirmHeadcount(response.intent?.headcount || 1)
          if (decisionOnly) {
            setPlanNodes([])
            setSelectedMerchantPlace(null)
            setPlanSummary(safeSummary(response))
          } else {
            applyPlanResponse(response)
          }

          if (event?.actionCard) {
            updateLoadingMessage(
              loadingMessageId,
              event.content || response.notificationText || response.summary || 'Plan updated',
              { actionCard: event.actionCard, planVariants },
            )
          } else {
            setChatMessages((messages) =>
              messages.map((message) =>
                message.id === loadingMessageId
                  ? {
                      ...message,
                      content: response.notificationText || response.summary || 'Plan updated',
                      planVariants,
                      isLoading: false,
                      isStreaming: true,
                    }
                  : message,
              ),
            )
          }

          if (!response.timeline?.length) {
            setCurrentPlan(response.planId ? response : null)
          }

          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          setChatMessages((messages) =>
            messages.map((message) =>
              message.id === loadingMessageId ? { ...message, isLoading: false } : message,
            ),
          )
          setSubmitError(error.message || 'Plan request failed')
          setPlanSummary('Request failed')
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
      },
    )
  }

  function handleBuildPuzzlePlanInternal(poiIds: string[], adjustmentText?: string, userMessage?: string) {
    if (isSubmitting) return

    const headcount = currentPlan?.intent?.headcount || 1
    const baseRequirement = requirement || 'User selected a recommended route'
    const prompt = adjustmentText
      ? `[BUILD_SELECTED_PLAN] 原始需求：${baseRequirement}。基于推荐的商家（商家ID: ${poiIds.join('、')}）生成行程拼图，总共 ${headcount} 个人，并且特殊要求：${adjustmentText}。`
      : `[BUILD_SELECTED_PLAN] 原始需求：${baseRequirement}。基于推荐的商家（商家ID: ${poiIds.join('、')}）生成行程拼图，总共 ${headcount} 个人。`

    streamCleanupRef.current?.()
    streamCleanupRef.current = null

    setIsSubmitting(true)
    setSubmitError(null)
    planVariantsRef.current = {}
    setCurrentPlan(null)
    setCurrentTimeline([])
    setPlanNodes([])
    setChatDraft('')
    setPlanSummary('Building adjusted plan...')
    setStage('planning')
    setIsConfirmModalOpen(false)
    setFailedOrderIds([])
    setSelectedMerchantPlace(null)
    resetPlanningColumns()

    const loadingMessageId = `planpal-build-${Date.now()}`
    setChatMessages((messages) => [
      ...messages,
      {
        id: `user-${Date.now()}`,
        role: 'user',
        content: userMessage || (adjustmentText ? `Adjust: ${adjustmentText}` : 'Use this recommendation'),
      },
      { id: loadingMessageId, role: 'planpal', content: 'Building adjusted plan...', isLoading: true },
    ])

    streamCleanupRef.current = requestPlanStream(
      { userId: DEFAULT_USER_ID, prompt },
      {
        onEvent: (event) => {
          setSseEvents((prev) => [...prev, event])
        },
        onTimeline: (response) => {
          const nodes = mapPlanResponseToNodes(response, [])
          setCurrentTimeline(response.timeline || [])
          setPlanNodes(nodes)
          setSelectedMerchantPlace((current) => current ?? nodes[0]?.place ?? null)
        },
        onFinish: (response, event) => {
          rememberPlanVariants(response)
          const planVariants = variantSummariesFromResponse(response)
          setCurrentPlan(response)
          setCurrentTimeline(response.timeline || [])
          setConfirmHeadcount(response.intent?.headcount || 1)
          applyPlanResponse(response)
          if (event?.actionCard) {
            updateLoadingMessage(
              loadingMessageId,
              event.content || response.notificationText || response.summary || 'Plan updated',
              { actionCard: event.actionCard, planVariants },
            )
          } else {
            setChatMessages((messages) =>
              messages.map((message) =>
                message.id === loadingMessageId
                  ? {
                      ...message,
                      content: response.notificationText || response.summary || 'Plan updated',
                      planVariants,
                      isLoading: false,
                      isStreaming: true,
                    }
                  : message,
              ),
            )
          }
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          setSubmitError(error.message || 'Adjusted plan request failed')
          setPlanSummary('Request failed')
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
    streamCleanupRef.current = null
    setIsSubmitting(true)
    setSubmitError(null)

    if (options?.clearDraft) setChatDraft('')
    if (options?.finishEditingNode) {
      setEditingNodeId(null)
      setNodeDraft('')
    }

    const loadingMessageId = `planpal-chat-${Date.now()}`
    setChatMessages((messages) => {
      const nextMessages: ChatMessage[] = [
      ...messages,
      ...(options?.userMessage
        ? [{ id: `user-${Date.now()}`, role: 'user' as const, content: options.userMessage }]
        : []),
      { id: loadingMessageId, role: 'planpal', content: 'Updating plan...', isLoading: true },
      ]
      return nextMessages
    })

    streamCleanupRef.current = requestPlanChatStream(
      currentPlan.planId,
      {
        ...payload,
        userId: payload.userId || DEFAULT_USER_ID,
        prompt: promptText || 'Please adjust the current plan.',
      },
      {
        onEvent: (event) => {
          setSseEvents((prev) => [...prev, event])
        },
        onTimeline: (response) => {
          const nodes = mapPlanResponseToNodes(response, [])
          setCurrentTimeline(response.timeline || [])
          setPlanNodes(nodes)
          setSelectedMerchantPlace((current) => current ?? nodes[0]?.place ?? null)
        },
        onFinish: (response, event) => {
          rememberPlanVariants(response)
          const planVariants = variantSummariesFromResponse(response)
          const decisionOnly = isDecisionOnlyResponse(response, event)
          const isDecisionRequired = Boolean(event?.type === 'FINISH' && event.actionCard)
          if (isDecisionRequired) {
            updateLoadingMessage(
              loadingMessageId,
              event?.content || response.notificationText || response.summary || 'Plan updated',
              { actionCard: event?.actionCard ?? null, planVariants },
            )
          } else {
            setChatMessages((messages) =>
              messages.map((message) =>
                message.id === loadingMessageId
                  ? {
                      ...message,
                      content: response.notificationText || response.summary || 'Plan updated',
                      actionCard: event?.actionCard ?? message.actionCard ?? null,
                      planVariants,
                      isLoading: false,
                      isStreaming: true,
                    }
                  : message,
              ),
            )
          }

          if (decisionOnly) {
            setCurrentPlan(response)
            setCurrentTimeline([])
            setPlanNodes([])
            setSelectedMerchantPlace(null)
            setConfirmHeadcount(response.intent?.headcount || 1)
            setPlanSummary(safeSummary(response))
          } else if (response.timeline?.length) {
            applyPlanResponse(response)
          } else if (response.planId) {
            setCurrentPlan((previous) => previous?.planId === response.planId ? { ...previous, ...response } : response)
          }

          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
        onError: (error) => {
          setChatMessages((messages) => messages.filter((message) => !message.isLoading))
          setSubmitError(error.message || 'Adjusted plan request failed')
          setPlanSummary('Request failed')
          setIsSubmitting(false)
          streamCleanupRef.current = null
        },
      },
    )
  }

  function handleChatSend(customText?: string) {
    const text = (customText ?? chatDraft).trim()
    if (!text) return

    if (!currentPlan?.planId) {
      const combined = requirement ? `${requirement}。${text}` : text
      submitRequirement(undefined, combined)
      return
    }

    runChatAdjustment(
      {
        userId: DEFAULT_USER_ID,
        prompt: text,
        source: 'chat-input',
      },
      { clearDraft: true, userMessage: text },
    )
  }

  function handleReset() {
    streamCleanupRef.current?.()
    streamCleanupRef.current = null
    setStage('intro')
    setPlanSummary('')
    setSubmitError(null)
    setIsSubmitting(false)
    planVariantsRef.current = {}
    setCurrentPlan(null)
    setCurrentTimeline([])
    setChatDraft('')
    setChatMessages([])
    setIsConfirmModalOpen(false)
    setFailedOrderIds([])
    setSelectedRouteChoices({})
    setSelectedMerchantPlace(null)
    setPlanNodes(basePlan)
    setRequirement('')
    setDraft('')
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
    selectPlanVariant,
  }
}
