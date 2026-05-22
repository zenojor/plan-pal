import { useEffect, useMemo, useRef, useState } from 'react'
import type { DragEvent, FormEvent } from 'react'
import { Button } from 'animal-island-ui'
import './index.css'
import { AgentStatusPanel } from './components/AgentStatusPanel'
import { ColumnHeader } from './components/ColumnHeader'
import { ColumnPicker } from './components/ColumnPicker'
import { DetailsColumn } from './components/DetailsColumn'
import { ExecutionActions } from './components/ExecutionActions'
import { IntroScreen } from './components/IntroScreen'
import { MapColumn } from './components/MapColumn'
import { MerchantColumn } from './components/MerchantColumn'
import { MobileBottomNav } from './components/MobileBottomNav'
import { PlanningHeader } from './components/PlanningHeader'
import { PuzzleColumn } from './components/PuzzleColumn'
import { executeAgentAction, streamAgentRun } from './services/agentApi'
import { alternatives, basePlan, columnMeta, examplePrompts } from './data/planData'
import type { AgentRun, AgentStreamEvent, ExecutionAction } from './types/agent'
import type { ColumnId, PlanNode, Stage } from './types/plan'

function App() {
  const [stage, setStage] = useState<Stage>('intro')
  const [requirement, setRequirement] = useState('')
  const [draft, setDraft] = useState('')
  const [planNodes, setPlanNodes] = useState<PlanNode[]>(basePlan)
  const [agentRun, setAgentRun] = useState<AgentRun | null>(null)
  const [isAgentLoading, setIsAgentLoading] = useState(false)
  const [agentError, setAgentError] = useState<string | null>(null)
  const [executingActionId, setExecutingActionId] = useState<string | null>(null)
  const [columns, setColumns] = useState<ColumnId[]>(['puzzle'])
  const [draggingColumn, setDraggingColumn] = useState<ColumnId | null>(null)
  const [draggingNodeId, setDraggingNodeId] = useState<string | null>(null)
  const [dragOverColumn, setDragOverColumn] = useState<ColumnId | null>(null)
  const [dragOverNodeId, setDragOverNodeId] = useState<string | null>(null)
  const [isColumnMenuOpen, setIsColumnMenuOpen] = useState(false)
  const [editingNodeId, setEditingNodeId] = useState<string | null>(null)
  const [selectedMerchantPlace, setSelectedMerchantPlace] = useState<string | null>(null)
  const [nodeDraft, setNodeDraft] = useState('')
  const [activeMobileTab, setActiveMobileTab] = useState<ColumnId>('puzzle')
  const columnContainerRef = useRef<HTMLDivElement>(null)

  const closedColumns = useMemo(
    () =>
      (Object.keys(columnMeta) as ColumnId[]).filter(
        (column) => column !== 'puzzle' && !columns.includes(column),
      ),
    [columns],
  )

  const scheduledNodes = useMemo(() => planNodes, [planNodes])

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (columnContainerRef.current && !columnContainerRef.current.contains(event.target as Node)) {
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

  async function submitRequirement(event?: FormEvent) {
    event?.preventDefault()
    const text = draft.trim()
    if (!text || isAgentLoading) return

    setRequirement(text)
    setAgentError(null)
    setIsAgentLoading(true)
    setPlanNodes([])
    setAgentRun(null)
    setColumns(['puzzle', 'details'])
    setSelectedMerchantPlace(null)
    setActiveMobileTab('puzzle')
    setStage('planning')

    try {
      await streamAgentRun(text, handleAgentStreamEvent)
    } catch (error) {
      setAgentRun(null)
      setPlanNodes(basePlan)
      setAgentError(error instanceof Error ? error.message : '后端 Agent 暂时不可用。')
    } finally {
      setIsAgentLoading(false)
    }
  }

  function handleAgentStreamEvent(event: AgentStreamEvent) {
    if (event.event === 'run_started') {
      setAgentRun(event.data)
      return
    }
    if (event.event === 'tool_call') {
      setAgentRun((current) =>
        current
          ? { ...current, tool_calls: [...current.tool_calls, event.data] }
          : current,
      )
      return
    }
    if (event.event === 'plan_node') {
      setPlanNodes((current) => [...current, event.data])
      setAgentRun((current) =>
        current
          ? { ...current, plan_nodes: [...current.plan_nodes, event.data] }
          : current,
      )
      return
    }
    if (event.event === 'route_segments') {
      setAgentRun((current) =>
        current ? { ...current, route_segments: event.data.segments } : current,
      )
      return
    }
    if (event.event === 'execution_actions') {
      setAgentRun((current) =>
        current ? { ...current, execution_actions: event.data.actions } : current,
      )
      return
    }
    if (event.event === 'completed') {
      setAgentRun(event.data)
      setPlanNodes(event.data.plan_nodes)
      if (event.data.status === 'needs_more_info') {
        setAgentError(event.data.message || '信息还不够，请补充时间、人数或场景。')
      }
      return
    }
    if (event.event === 'error') {
      setAgentError(event.data.message)
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
              status: '已按描述改',
            }
          : node,
      ),
    )
    setEditingNodeId(null)
    setNodeDraft('')
  }

  async function executeAction(actionId: string) {
    setExecutingActionId(actionId)
    try {
      const updated = await executeAgentAction(actionId)
      setAgentRun((current) => updateActionInRun(current, updated))
    } catch (error) {
      setAgentError(error instanceof Error ? error.message : '执行动作失败，请稍后重试。')
    } finally {
      setExecutingActionId(null)
    }
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
      const fromIndex = nodes.findIndex((node) => node.id === draggingNodeId)
      if (fromIndex < 0) return nodes
      const next = [...nodes]
      const [movedNode] = next.splice(fromIndex, 1)
      if (targetNodeId === '__end__') {
        next.push(movedNode)
      } else {
        const toIndex = nodes.findIndex((node) => node.id === targetNodeId)
        next.splice(toIndex < 0 ? next.length : toIndex, 0, movedNode)
      }
      return next
    })
    setDraggingNodeId(null)
  }

  function moveNodeUp(nodeId: string) {
    setPlanNodes((nodes) => {
      const index = nodes.findIndex((node) => node.id === nodeId)
      if (index <= 0) return nodes
      const next = [...nodes]
      const [moved] = next.splice(index, 1)
      next.splice(index - 1, 0, moved)
      return next
    })
  }

  function moveNodeDown(nodeId: string) {
    setPlanNodes((nodes) => {
      const index = nodes.findIndex((node) => node.id === nodeId)
      if (index < 0 || index >= nodes.length - 1) return nodes
      const next = [...nodes]
      const [moved] = next.splice(index, 1)
      next.splice(index + 1, 0, moved)
      return next
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
  }[columns.length]

  if (stage === 'intro') {
    return (
      <IntroScreen
        draft={draft}
        examplePrompts={examplePrompts}
        onDraftChange={setDraft}
        onSubmit={submitRequirement}
      />
    )
  }

  return (
    <main className="flex flex-col h-screen min-h-0 bg-animal-grid bg-animal-bg overflow-hidden">
      <PlanningHeader
        requirement={requirement}
        stage={stage}
        onConfirm={() => setStage('confirmed')}
        onReset={() => setStage('intro')}
      />

      <section
        className={`grid grid-cols-1 items-stretch flex-1 min-h-0 gap-0 md:gap-3.5 mx-auto w-full px-0 pt-0 pb-0 md:px-3.5 md:pt-3.5 md:pb-[76px] overflow-y-hidden overflow-x-hidden md:overflow-x-auto ${boardColsClass}`}
      >
        {renderColumns()}
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
          onClick={() => setStage('confirmed')}
        >
          {stage === 'confirmed' ? '已确认' : '确认方案'}
        </Button>
      </footer>

      <MobileBottomNav activeMobileTab={activeMobileTab} onTabChange={setActiveMobileTab} />
    </main>
  )

  function renderColumns() {
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
            if (draggingColumn) allowDrop(event)
          }}
          onDragEnter={() => {
            if (draggingColumn && draggingColumn !== column) setDragOverColumn(column)
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
                nodes={scheduledNodes}
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
                nodes={scheduledNodes}
                profiles={agentRun?.merchant_profiles}
                selectedPlace={selectedMerchantPlace}
                onSelectPlace={setSelectedMerchantPlace}
              />
            )}
            {column === 'details' && (
              <div className="flex flex-col flex-1 min-h-0 overflow-y-auto overscroll-contain custom-scrollbar pb-[100px] md:pb-0">
                <AgentStatusPanel run={agentRun} isLoading={isAgentLoading} error={agentError} />
                <ExecutionActions
                  actions={agentRun?.execution_actions ?? []}
                  executingActionId={executingActionId}
                  onExecute={executeAction}
                />
                <DetailsColumn nodes={scheduledNodes} />
              </div>
            )}
            {column === 'map' && <MapColumn nodes={scheduledNodes} />}
          </div>
        </section>
      )
    })
  }
}

function updateActionInRun(current: AgentRun | null, updated: ExecutionAction): AgentRun | null {
  if (!current) return current
  return {
    ...current,
    status: 'done',
    execution_actions: current.execution_actions.map((action) =>
      action.id === updated.id ? updated : action,
    ),
  }
}

export default App
