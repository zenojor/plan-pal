import { Button, Input, Modal } from 'animal-island-ui'
import type { AgentOrderIntent, AgentPlanStep } from '../api/agent'

type ConfirmOrderModalProps = {
  failedOrders?: string[]
  headcount: number
  isConfirming?: boolean
  notificationText: string
  open: boolean
  orderIntents: AgentOrderIntent[]
  timeline: AgentPlanStep[]
  onClose: () => void
  onConfirm: () => void
  onHeadcountChange: (value: number) => void
}

function orderLabel(type: string) {
  if (type === 'BOOK_TICKET') return '门票'
  if (type === 'RESERVE_TABLE') return '订座'
  return '确认'
}

export function ConfirmOrderModal({
  failedOrders = [],
  headcount,
  isConfirming = false,
  notificationText,
  open,
  orderIntents,
  timeline,
  onClose,
  onConfirm,
  onHeadcountChange,
}: ConfirmOrderModalProps) {
  const executableItems = orderIntents.filter((item) => item.type !== 'CHECK_ONLY')

  return (
    <Modal
      open={open}
      title="确认下单内容"
      width={680}
      typewriter={false}
      onClose={onClose}
      footer={
        <div className="flex flex-wrap justify-end gap-3">
          <Button type="default" onClick={onClose} disabled={isConfirming} className="disabled:cursor-not-allowed">
            再看看
          </Button>
          <Button
            type="primary"
            loading={isConfirming}
            disabled={isConfirming}
            className="bg-[#ffcc00]! border-[#ffcc00]! text-[#725d42]! shadow-[0_5px_0_0_#dba90e]! disabled:cursor-not-allowed"
            onClick={onConfirm}
          >
            确认下单
          </Button>
        </div>
      }
    >
      <div className="space-y-4 text-[#725d42]">
        <div className="rounded-[24px] border-2 border-[#c4b89e] bg-[#fff9e8] p-4 shadow-[0_3px_0_0_#d4c9b4]">
          <label className="block text-sm font-black text-[#794f27]">下单人数</label>
          <div className="mt-2 max-w-[180px]">
            <Input
              value={String(headcount)}
              onChange={(event) => {
                const next = Number.parseInt(event.target.value.replace(/\D/g, ''), 10)
                onHeadcountChange(Number.isFinite(next) && next > 0 ? next : 1)
              }}
            />
          </div>
        </div>

        <div className="space-y-2">
          {executableItems.length === 0 ? (
            <div className="rounded-[24px] border-2 border-[#82d5bb] bg-[#e6f9f6] p-4 text-sm font-bold shadow-[0_3px_0_0_#82d5bb]">
              这份计划没有需要 mock 下单的商品，我会只发送行程通知。
            </div>
          ) : (
            executableItems.map((item) => {
              const step = timeline.find((entry) => entry.orderIntentId === item.orderIntentId)
              const failed = failedOrders.includes(item.orderIntentId)
              return (
                <div
                  key={item.orderIntentId}
                  className={`rounded-[24px] border-2 p-4 shadow-[0_3px_0_0_#d4c9b4] ${
                    failed ? 'border-[#e05a5a] bg-[#fff0f0]' : 'border-[#c4b89e] bg-[#f7f3df]'
                  }`}
                >
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <strong className="text-[#794f27] text-base font-black">{item.poiName}</strong>
                    <span className="rounded-full bg-[#e6f9f6] px-3 py-1 text-[12px] font-black text-[#11a89b]">
                      {orderLabel(item.type)}
                    </span>
                  </div>
                  <p className="m-0 mt-1 text-sm font-bold">
                    {step?.startTime || item.targetTime} · {headcount} 人 · {step?.budget || '按现场确认'}
                  </p>
                  <p className="m-0 mt-1 text-xs font-bold text-[#9f927d]">
                    {failed ? '上一轮执行失败，可重试。' : step?.note || '确认后模拟锁定资源并发送通知。'}
                  </p>
                </div>
              )
            })
          )}
        </div>

        <div className="rounded-[20px] bg-[#e6f9f6] px-4 py-3 text-sm font-bold leading-relaxed text-[#0f4c46]">
          通知文案：{notificationText || '确认后会生成一条行程通知。'}
        </div>
      </div>
    </Modal>
  )
}
