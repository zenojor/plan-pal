import { useState } from 'react'
import type { DragEvent } from 'react'
import type { PlanNode, SelectedRouteChoice } from '../types/plan'
import type { AgentPlanPatch, AgentPlanChatRequest } from '../api/agent'

export function useTimelineOperations(dependencies: {
  planNodes: PlanNode[]
  runChatAdjustment: (
    payload: AgentPlanChatRequest,
    options?: { clearDraft?: boolean; finishEditingNode?: boolean; userMessage?: string }
  ) => void
}) {
  const [draggingNodeId, setDraggingNodeId] = useState<string | null>(null)
  const [dragOverNodeId, setDragOverNodeId] = useState<string | null>(null)
  const [editingNodeId, setEditingNodeId] = useState<string | null>(null)
  const [nodeDraft, setNodeDraft] = useState('')
  const [selectedMerchantPlace, setSelectedMerchantPlace] = useState<string | null>(null)
  const [selectedRouteChoices, setSelectedRouteChoices] = useState<Record<string, SelectedRouteChoice>>({})

  const { planNodes, runChatAdjustment } = dependencies

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
    const patch: AgentPlanPatch = {
      intent: 'MODIFY_PLAN',
      editType: 'REPLACE',
      target: {
        segmentId: node.segmentId,
      },
      requirements: {
        keep: [],
        avoid: [],
        prefer: [],
        endEarlier: false,
      },
      requiresSearch: true,
    }

    runChatAdjustment(
      {
        userId: 'U001',
        prompt: `换掉“${node.title}”`,
        segmentId: node.segmentId,
        source: 'puzzle-replace-preview',
        patch,
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

  function allowDrop(event: DragEvent<HTMLElement>) {
    event.preventDefault()
  }

  return {
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
    applyNodeRewrite,
    handleNodeDrop,
    moveNodeUp,
    moveNodeDown,
    allowDrop,
  }
}
