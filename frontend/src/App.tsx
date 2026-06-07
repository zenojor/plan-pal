import { useEffect, useMemo, useRef } from 'react'
import { Button } from 'animal-island-ui'
import type { AgentPlanPatch } from './api/agent'
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
import { API_BASE_URL, DEFAULT_USER_ID } from './config/api'
import { basePlan, examplePrompts } from './data/planData'
import { useWorkspaceColumns } from './hooks/useWorkspaceColumns'
import { useChatMessages } from './hooks/useChatMessages'
import { useConfirmOrder } from './hooks/useConfirmOrder'
import { useTimelineOperations } from './hooks/useTimelineOperations'
import { usePlanStream } from './hooks/usePlanStream'
import {
  orderedTimelineForCurrentNodes,
  orderIntentsForCurrentTimeline,
  rebuildTimelineWithTransit,
} from './utils/timelineHelper'
import type { ChatMessage } from './types/plan'
import './index.css'

function App() {
  const { chatDraft, setChatDraft, chatMessages, setChatMessages } = useChatMessages()
  const selectMerchantPlaceRef = useRef<(name: string) => void>(() => {})

  const {
    activeMobileTab,
    addColumn,
    closedColumns,
    columnContainerRef,
    columns,
    dragOverColumn,
    draggingColumn,
    handleColumnDrop,
    isColumnMenuOpen,
    openMerchantColumn,
    removeColumn,
    resetPlanningColumns,
    setActiveMobileTab,
    setDragOverColumn,
    setDraggingColumn,
    setIsColumnMenuOpen,
  } = useWorkspaceColumns((name) => {
    selectMerchantPlaceRef.current(name)
  })

  const {
    stage,
    setStage,
    requirement,
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
    submitError,
    setSubmitError,
    sseEvents,
    setSseEvents,
    submitRequirement,
    handleBuildPuzzlePlanInternal,
    runChatAdjustment,
    handleReset,
    handleChatSend,
    selectPlanVariant,
  } = usePlanStream({
    basePlan,
    resetPlanningColumns,
    setActiveMobileTab,
    chatDraft,
    setChatDraft,
    chatMessages,
    setChatMessages,
    setConfirmHeadcount: (val) => setConfirmHeadcount(val),
    setIsConfirmModalOpen: (val) => setIsConfirmModalOpen(val),
    setFailedOrderIds: (val) => setFailedOrderIds(val),
    setSelectedMerchantPlace: (val) => setSelectedMerchantPlace(val),
    setSelectedRouteChoices: (val) => setSelectedRouteChoices(val),
    setEditingNodeId: (val) => setEditingNodeId(val),
    setNodeDraft: (val) => setNodeDraft(val),
  })

  const {
    draggingNodeId,
    setDraggingNodeId,
    dragOverNodeId,
    setDragOverNodeId,
    editingNodeId,
    setEditingNodeId,
    nodeDraft,
    setNodeDraft,
    selectedMerchantPlace,
    setSelectedMerchantPlace,
    selectedRouteChoices,
    setSelectedRouteChoices,
    replaceNode,
    recommendFreeSlot,
    deleteNode,
    applyNodeRewrite,
    handleNodeDrop,
    moveNodeUp,
    moveNodeDown,
    allowDrop,
  } = useTimelineOperations({
    planNodes,
    setPlanNodes,
    runChatAdjustment,
    userId: DEFAULT_USER_ID,
  })

  useEffect(() => {
    selectMerchantPlaceRef.current = setSelectedMerchantPlace
  }, [setSelectedMerchantPlace])

  const orderedTimeline = useMemo(() => {
    return orderedTimelineForCurrentNodes(
      planNodes,
      currentTimeline,
      selectedRouteChoices,
      currentPlan?.intent?.headcount || 1
    )
  }, [planNodes, currentTimeline, selectedRouteChoices, currentPlan])

  const orderIntents = useMemo(() => {
    return orderIntentsForCurrentTimeline(
      planNodes,
      currentTimeline,
      selectedRouteChoices,
      1, // default confirm headcount placeholder; updated dynamically by useConfirmOrder
      currentPlan?.intent?.headcount || 1
    )
  }, [planNodes, currentTimeline, selectedRouteChoices, currentPlan])

  const {
    isConfirmModalOpen,
    setIsConfirmModalOpen,
    confirmHeadcount,
    setConfirmHeadcount,
    failedOrderIds,
    setFailedOrderIds,
    isConfirming,
    openConfirmModal,
    confirmCurrentPlan,
  } = useConfirmOrder({
    currentPlan,
    setCurrentPlan,
    isSubmitting,
    stage,
    setStage,
    planSummary,
    setPlanSummary,
    planNodes,
    setPlanNodes,
    setCurrentTimeline,
    setSseEvents,
    setSubmitError,
    orderedTimeline,
  })

  // Dynamic overrides for orderIntents based on actual user-selected headcount
  const dynamicOrderIntents = useMemo(() => {
    return orderIntents.map(intent => ({
      ...intent,
      headcount: confirmHeadcount || currentPlan?.intent?.headcount || 1
    }))
  }, [orderIntents, confirmHeadcount, currentPlan])

  const pendingDecisionCard = useMemo(() => {
    for (let index = chatMessages.length - 1; index >= 0; index -= 1) {
      const message = chatMessages[index]
      if (message.role !== 'planpal') continue
      return message.actionCard?.options?.length ? message.actionCard : null
    }
    return null
  }, [chatMessages])

  const pendingDecisionReason = pendingDecisionCard
    ? pendingDecisionCard.cardKind === 'SLOT_COLLECTION'
      ? '先补充聊天列里的必要信息，再确认方案'
      : '先在聊天列选定候选，再确认方案'
    : ''

  const confirmDisabled =
    !currentPlan?.planId || isSubmitting || isConfirming || stage === 'confirmed' || Boolean(pendingDecisionCard)

  const confirmTitle =
    pendingDecisionReason ||
    (stage === 'confirmed' ? '已下单' : isConfirming ? '执行中' : isSubmitting ? '更新方案中' : '确认方案')

  function handleOpenConfirmModal() {
    if (confirmDisabled) {
      if (pendingDecisionReason) setSubmitError(pendingDecisionReason)
      return
    }
    openConfirmModal()
  }

  function executeActionCardOption(
    _messageId: string,
    option: NonNullable<ChatMessage['actionCard']>['options'][number],
  ) {
    if (option.actionType === 'BUILD_PLAN') {
      if (option.poiIds?.length) {
        handleBuildPuzzlePlanInternal(option.poiIds, undefined, option.label)
        return
      }

      runChatAdjustment(
        {
          userId: DEFAULT_USER_ID,
          prompt: option.prompt || option.label || 'BUILD_PLAN',
          source: 'action-card:BUILD_PLAN',
          clientActionId: option.id,
        },
        {
          userMessage: option.label,
        },
      )
      return
    }

    if (option.actionType === 'SELECT_PREFERENCE' || option.actionType === 'REQUEST_POI_RESEARCH') {
      runChatAdjustment(
        {
          userId: DEFAULT_USER_ID,
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
      return
    }

    runChatAdjustment(
      {
        userId: DEFAULT_USER_ID,
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

  function sendStructuredPrompt(prompt: string, context?: { source?: string; userMessage?: string }) {
    runChatAdjustment(
      {
        userId: DEFAULT_USER_ID,
        prompt,
        source: context?.source || 'chat-inline',
      },
      {
        userMessage: context?.userMessage || prompt,
      },
    )
  }

  function handleOpenMap() {
    addColumn('map')
    setActiveMobileTab('map')
    setTimeout(() => {
      const el = document.querySelector('[data-column-id="map"]')
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' })
      }
    }, 100)
  }

  function handleOpenMerchant(place: string) {
    openMerchantColumn(place)
    setTimeout(() => {
      const el = document.querySelector('[data-column-id="merchant"]')
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' })
      }
    }, 100)
  }

  const boardColsClass = {
    1: 'md:w-[min(1680px,calc(100%-108px))] md:grid-cols-[minmax(320px,620px)] md:justify-center',
    2: 'md:w-[min(1120px,calc(100%-108px))] md:grid-cols-[repeat(2,minmax(0,1fr))] md:justify-center',
    3: 'md:w-[min(1680px,calc(100%-108px))] md:grid-cols-[repeat(3,minmax(0,1fr))]',
    4: 'md:w-[min(1680px,calc(100%-108px))] md:grid-cols-[repeat(4,minmax(0,1fr))]',
    5: 'md:w-[min(1900px,calc(100%-108px))] md:grid-cols-[repeat(5,minmax(0,1fr))]',
  }[columns.length]

  const renderChatColumn = () => (
    <PlanPalChatColumn
      draft={chatDraft}
      onExecuteActionCardOption={executeActionCardOption}
      isDisabled={isSubmitting}
      messages={chatMessages}
      onSelectPlanVariant={selectPlanVariant}
      onDraftChange={setChatDraft}
      onSend={handleChatSend}
      onSendStructuredPrompt={sendStructuredPrompt}
      onOpenMerchant={handleOpenMerchant}
      onBuildPuzzlePlan={(poiIds) => handleBuildPuzzlePlanInternal(poiIds)}
      onBuildAdjustedPuzzlePlan={(poiIds, adj) => handleBuildPuzzlePlanInternal(poiIds, adj)}
    />
  )

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
        confirmDisabled={confirmDisabled}
        confirmTitle={confirmTitle}
        requirement={requirement}
        isConfirming={isConfirming}
        summary={planSummary}
        stage={stage}
        onConfirm={handleOpenConfirmModal}
        onReset={handleReset}
      />

      <section className="md:hidden shrink-0 min-h-[260px] h-[calc((100svh-64px-74px)/2)] max-h-[420px] px-2.5 pt-2.5 pb-2">
        <div className="flex h-full min-h-0 flex-col overflow-hidden rounded-[24px] border-2 border-[rgba(196,184,158,0.78)] bg-[#f7f3df] shadow-[0_4px_0_0_#d4c9b4,0_12px_28px_rgba(61,52,40,0.09)]">
          {renderChatColumn()}
        </div>
      </section>

      <section
        className={`grid grid-cols-1 items-stretch flex-1 min-h-0 gap-0 md:gap-3.5 mx-auto w-full px-0 pt-0 pb-[74px] md:px-3.5 md:pt-3.5 md:pb-[76px] overflow-y-hidden overflow-x-hidden md:overflow-x-auto ${boardColsClass}`}
      >
        {(() => {
          const columnsToRender = [...columns]
          if (!columnsToRender.includes(activeMobileTab)) {
            columnsToRender.push(activeMobileTab)
          }

          return columnsToRender.map((column) => {
            const isDesktopActive = columns.includes(column)
            const isMobileActive = activeMobileTab === column

            if (column === 'chat') {
              if (!isDesktopActive) return null
            } else if (!isDesktopActive && !isMobileActive) return null

            let visibilityClass = 'hidden'
            if (column === 'chat') {
              visibilityClass = 'hidden md:flex'
            } else if (isDesktopActive && isMobileActive) {
              visibilityClass = 'flex'
            } else if (isDesktopActive) {
              visibilityClass = 'hidden md:flex'
            } else if (isMobileActive) {
              visibilityClass = 'flex md:hidden'
            }

            return (
              <section
                data-column-id={column}
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
                      onDelete={deleteNode}
                      onEdit={(nodeId) => {
                        setEditingNodeId(nodeId)
                        setNodeDraft('')
                      }}
                       onMoveDown={moveNodeDown}
                      onMoveUp={moveNodeUp}
                      onOpenMerchant={handleOpenMerchant}
                      onOpenMap={handleOpenMap}
                      onRecommendFreeSlot={recommendFreeSlot}
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
                  {column === 'map' && (
                    <MapColumn
                      nodes={planNodes}
                      selectedRouteChoices={selectedRouteChoices}
                      onRouteChoiceChange={(segmentKey, choice) => {
                        setSelectedRouteChoices((current) => {
                          const nextChoices = { ...current, [segmentKey]: choice }
                          setPlanNodes((nodes) => rebuildTimelineWithTransit(nodes, nextChoices))
                          return nextChoices
                        })
                      }}
                    />
                  )}
                  {column === 'dev' && (
                    <DevColumn
                      plan={currentPlan}
                      nodes={planNodes}
                      events={sseEvents}
                      onClearEvents={() => setSseEvents([])}
                    />
                  )}
                  {column === 'chat' && (
                    renderChatColumn()
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
          disabled={confirmDisabled}
          title={confirmTitle}
          onClick={handleOpenConfirmModal}
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
        orderIntents={dynamicOrderIntents}
        timeline={orderedTimeline}
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
