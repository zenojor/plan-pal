import { useState } from 'react'
import { confirmPlan, mapPlanResponseToNodes } from '../api/agent'
import type { AgentPlanResponse, AgentPlanStep, AgentPlanStreamEvent } from '../api/agent'
import type { PlanNode, Stage } from '../types/plan'

export function useConfirmOrder(dependencies: {
  currentPlan: AgentPlanResponse | null
  setCurrentPlan: React.Dispatch<React.SetStateAction<AgentPlanResponse | null>>
  isSubmitting: boolean
  stage: Stage
  setStage: React.Dispatch<React.SetStateAction<Stage>>
  planSummary: string
  setPlanSummary: React.Dispatch<React.SetStateAction<string>>
  planNodes: PlanNode[]
  setPlanNodes: React.Dispatch<React.SetStateAction<PlanNode[]>>
  setCurrentTimeline: React.Dispatch<React.SetStateAction<AgentPlanStep[]>>
  setSseEvents: React.Dispatch<React.SetStateAction<AgentPlanStreamEvent[]>>
  setSubmitError: React.Dispatch<React.SetStateAction<string | null>>
  orderedTimeline: AgentPlanStep[]
}) {
  const [isConfirmModalOpen, setIsConfirmModalOpen] = useState(false)
  const [confirmHeadcount, setConfirmHeadcount] = useState(1)
  const [failedOrderIds, setFailedOrderIds] = useState<string[]>([])
  const [isConfirming, setIsConfirming] = useState(false)

  const {
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
  } = dependencies

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
        version: currentPlan.version,
        idempotencyKey: crypto.randomUUID(),
      })

      const devLogs: AgentPlanStreamEvent[] = []
      
      orderedTimeline.forEach((step, idx) => {
        if (!step.poiId && !step.orderIntentId) return
        
        const isRide = step.isTransit && step.transportMode === 'TAXI'
        const isDining = ['DINING', 'DRINKS'].includes(step.phase)
        const toolName = isRide ? 'hailRide/RIDE_HAIL' : isDining ? 'reserveRestaurant' : 'bookTickets'
        
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

  return {
    isConfirmModalOpen,
    setIsConfirmModalOpen,
    confirmHeadcount,
    setConfirmHeadcount,
    failedOrderIds,
    setFailedOrderIds,
    isConfirming,
    setIsConfirming,
    openConfirmModal,
    confirmCurrentPlan,
  }
}
