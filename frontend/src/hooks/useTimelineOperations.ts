import { useState } from 'react'
import type { DragEvent } from 'react'
import type { PlanNode, SelectedRouteChoice } from '../types/plan'
import type { AgentPlanPatch, AgentPlanChatRequest } from '../api/agent'
import { rebuildTimelineWithTransit } from '../utils/timelineHelper'

export function useTimelineOperations(dependencies: {
  planNodes: PlanNode[]
  setPlanNodes: React.Dispatch<React.SetStateAction<PlanNode[]>>
  userId: string
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

  const { planNodes, runChatAdjustment, setPlanNodes, userId } = dependencies

  function isBufferNode(node: PlanNode) {
    return node.executionStatus === 'BUFFER'
      || (!node.poiId && node.phase === 'LEISURE' && node.source === 'system')
      || node.title.includes('预留机动时间')
      || node.place.includes('预留机动时间')
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
    return planNodes.filter((node) => !node.isTransit && !isBufferNode(node) && !!node.segmentId)
  }

  function moveBusinessNodeLocally(
    movedNodeId: string,
    anchorNodeId: string | null,
    position: 'BEFORE' | 'AFTER' | 'START' | 'END',
  ) {
    setPlanNodes((currentNodes) => {
      const movableNodes = currentNodes.filter((node) => !node.isTransit && !isBufferNode(node) && !!node.segmentId)
      const fromIndex = movableNodes.findIndex((node) => node.id === movedNodeId)
      if (fromIndex < 0) return currentNodes

      const reordered = [...movableNodes]
      const [movedNode] = reordered.splice(fromIndex, 1)
      if (!movedNode) return currentNodes

      let insertIndex = reordered.length
      if (position === 'START') {
        insertIndex = 0
      } else if (position !== 'END' && anchorNodeId) {
        const anchorIndex = reordered.findIndex((node) => node.id === anchorNodeId)
        if (anchorIndex < 0) return currentNodes
        insertIndex = position === 'AFTER' ? anchorIndex + 1 : anchorIndex
      }

      reordered.splice(Math.max(0, Math.min(insertIndex, reordered.length)), 0, movedNode)

      const movableIds = new Set(movableNodes.map((node) => node.id))
      const retainedNodes = currentNodes.filter((node) => !node.isTransit && !movableIds.has(node.id))
      return [...rebuildTimelineWithTransit(reordered, selectedRouteChoices), ...retainedNodes]
    })
  }

  function replaceNode(nodeId: string) {
    const node = planNodes.find((item) => item.id === nodeId)
    if (!node?.segmentId || node.isTransit || isBufferNode(node)) return
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
        userId: userId,
        prompt: `换掉“${node.title}”`,
        segmentId: node.segmentId,
        source: 'puzzle-replace-preview',
        patch,
      },
      { userMessage: `换掉“${node.title}”` },
    )
  }

  function recommendFreeSlot(nodeId: string) {
    const node = planNodes.find((item) => item.id === nodeId)
    if (!node?.segmentId || node.isTransit || node.poiId || node.phase !== 'LEISURE') return
    const patch: AgentPlanPatch = {
      intent: 'MODIFY_PLAN',
      editType: 'REPLACE',
      target: {
        segmentId: node.segmentId,
        activityType: 'ACTIVITY',
        phase: 'ACTIVITY',
      },
      requirements: {
        keep: [],
        avoid: [],
        prefer: ['NEARBY', 'FLEXIBLE_ACTIVITY'],
        endEarlier: false,
      },
      requiresSearch: true,
    }

    runChatAdjustment(
      {
        userId: userId,
        prompt: '给这段机动时间加点别的',
        segmentId: node.segmentId,
        source: 'puzzle-free-slot-recommend',
        patch,
      },
      { userMessage: '给这段机动时间加点别的' },
    )
  }

  function deleteNode(nodeId: string) {
    const node = planNodes.find((item) => item.id === nodeId)
    if (!node?.segmentId || node.isTransit || isBufferNode(node)) return
    if (businessNodesFromPlan().length <= 1) return
    const patch: AgentPlanPatch = {
      intent: 'MODIFY_PLAN',
      editType: 'DELETE',
      target: {
        segmentId: node.segmentId,
        phase: node.phase,
      },
      requirements: {
        keep: [],
        avoid: [],
        prefer: [],
        endEarlier: false,
      },
      requiresSearch: false,
    }

    runChatAdjustment(
      {
        userId: userId,
        prompt: `删除“${node.title}”`,
        segmentId: node.segmentId,
        source: 'puzzle-delete',
        patch,
      },
      { userMessage: `删除“${node.title}”` },
    )
  }

  function applyNodeRewrite(nodeId: string) {
    const text = nodeDraft.trim()
    const node = planNodes.find((item) => item.id === nodeId)
    if (!text || !node?.segmentId) return

    runChatAdjustment(
      {
        userId: userId,
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
    moveBusinessNodeLocally(draggingNodeId, targetNodeId === '__end__' ? null : targetNodeId, targetNodeId === '__end__' ? 'END' : 'BEFORE')

    runChatAdjustment({
      userId: userId,
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

    moveBusinessNodeLocally(movedNode.id, anchorNode.id, 'BEFORE')
    runChatAdjustment({
      userId: userId,
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

    moveBusinessNodeLocally(movedNode.id, anchorNode.id, 'AFTER')
    runChatAdjustment({
      userId: userId,
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
    recommendFreeSlot,
    deleteNode,
    applyNodeRewrite,
    handleNodeDrop,
    moveNodeUp,
    moveNodeDown,
    allowDrop,
  }
}
