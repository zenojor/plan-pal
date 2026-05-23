import { Button, Card, Input } from 'animal-island-ui'
import { useState } from 'react'
import type { ChatMessage } from '../types/plan'

type PlanPalChatColumnProps = {
  draft: string
  isDisabled?: boolean
  messages: ChatMessage[]
  onDraftChange: (value: string) => void
  onSend: () => void
  onOpenMerchant?: (name: string) => void
  onBuildPuzzlePlan?: (poiIds: string[]) => void
  onBuildAdjustedPuzzlePlan?: (poiIds: string[], adjustmentText: string) => void
}

export function PlanPalChatColumn({
  draft,
  isDisabled = false,
  messages,
  onDraftChange,
  onSend,
  onOpenMerchant,
  onBuildPuzzlePlan,
  onBuildAdjustedPuzzlePlan,
}: PlanPalChatColumnProps) {

  // 为每个消息气泡管理独立的微调输入框状态，防止多轮对话冲突
  const [tweaks, setTweaks] = useState<Record<string, string>>({})

  // 解析并渲染包含特殊商家标签 [POI:id:name] 的富文本
  function parseAndRenderContent(content: string) {
    const poiRegex = /\[POI:([^\]:]+):([^\]]+)\]/g
    const parts = []
    let lastIndex = 0
    let match

    while ((match = poiRegex.exec(content)) !== null) {
      const textBefore = content.substring(lastIndex, match.index)
      if (textBefore) {
        parts.push(<span key={lastIndex} className="whitespace-pre-wrap">{textBefore}</span>)
      }

      const poiId = match[1]
      const poiName = match[2]

      parts.push(
        <span
          key={`${poiId}-${match.index}`}
          className="inline-flex items-center gap-1 mx-1 px-2.5 py-1.5 rounded-[12px] border border-[#c4b89e] bg-[#fffdf5] text-[#794f27] text-xs font-black shadow-[0_2px_0_0_#d4c9b4] hover:bg-[#ffeea0] active:translate-y-[1px] active:shadow-none transition-all cursor-pointer select-none"
          onClick={() => onOpenMerchant?.(poiName)}
          title={`点击查看 ${poiName} 详情`}
        >
          🍃 {poiName}
        </span>
      )

      lastIndex = poiRegex.lastIndex
    }

    const textAfter = content.substring(lastIndex)
    if (textAfter) {
      parts.push(<span key={lastIndex} className="whitespace-pre-wrap">{textAfter}</span>)
    }

    return parts.length > 0 ? parts : content
  }

  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-hidden bg-[#f7f3df]">
      <div className="flex-1 min-h-0 overflow-y-auto custom-scrollbar p-4 pb-3 space-y-3">
        {messages.length === 0 && (
          <Card className="rounded-[24px]! border-2! border-[#c4b89e]! bg-[#fff9e8]! p-4! text-[#725d42]! shadow-[0_4px_0_0_#d4c9b4]! hover:!translate-y-0">
            <span className="inline-flex rounded-full bg-[#e6f9f6] px-3 py-1 text-[12px] font-black text-[#11a89b]">
              PlanPal
            </span>
            <h3 className="m-0 mt-2 text-[#794f27] text-lg font-black">和我说你想怎么改</h3>
            <p className="m-0 mt-1 text-sm font-semibold leading-relaxed">
              比如“吃饭太远，换个近一点的烧烤”或“我想去哪儿约会，求推荐安静清吧”。我会快速为您探索，或直接定制出行方案。
            </p>
          </Card>
        )}

        {messages.map((message) => {
          const isPlanPal = message.role === 'planpal'
          const hasCta = isPlanPal && message.content.includes('我可以为你构建完整的拼图方案')

          return (
            <div
              key={message.id}
              className={`flex flex-col ${message.role === 'user' ? 'items-end' : 'items-start'}`}
            >
              <div
                className={`max-w-[95%] rounded-[22px] border-2 px-4 py-3 text-sm font-bold leading-relaxed shadow-[0_3px_0_0_#d4c9b4] ${
                  message.role === 'user'
                    ? 'border-[#82d5bb] bg-[#e6f9f6] text-[#0f4c46]'
                    : 'border-[#c4b89e] bg-[#fff9e8] text-[#725d42]'
                }`}
              >
                {isPlanPal ? parseAndRenderContent(message.content) : message.content}

                {/* Coding-Agent 级 “同意并构建 / 微调输入” 组合卡片 */}
                {hasCta && (
                  <div className="mt-3.5 pt-3.5 border-t-2 border-[#c4b89e]/30 flex flex-col gap-2.5 bg-[#fcfaf2]/80 border-2 border-[#c4b89e]/60 rounded-[16px] p-3 shadow-inner">
                    <div className="flex items-center justify-between">
                      <span className="text-[11px] font-black text-[#725d42]/70 uppercase tracking-wider">
                        📋 行程拼图一键合成申请 (Action Request)
                      </span>
                    </div>

                    {/* 上部动作按钮：同意构建 */}
                    <Button
                      type="primary"
                      className="w-full bg-[#2b6cb0]! border-[#2b6cb0]! text-[#fff]! shadow-[0_4px_0_0_#1a365d]! hover:scale-[1.01] active:scale-[0.99] active:translate-y-[1px] active:shadow-none transition-all duration-150 font-black text-sm py-2.5! h-auto! rounded-[12px]! flex justify-center items-center gap-1.5"
                      onClick={() => {
                        const matchedPoiIds = Array.from(
                          message.content.matchAll(/\[POI:([^\]:]+):[^\]]+\]/g)
                        ).map((match) => match[1])

                        if (onBuildPuzzlePlan && matchedPoiIds.length > 0) {
                          onBuildPuzzlePlan(matchedPoiIds)
                        }
                      }}
                    >
                      🎨 同意并构建行程 (Accept & Build)
                    </Button>

                    <div className="border-t border-[#c4b89e]/30 my-0.5"></div>

                    {/* 下部微调输入栏 */}
                    <div className="flex gap-2">
                      <input
                        type="text"
                        disabled={isDisabled}
                        className="flex-1 px-3.5 py-2 border-2 border-[#c4b89e] rounded-[12px] bg-[#fdfcf7] text-xs font-bold text-[#725d42] placeholder-[#9f927d]/80 outline-none focus:border-[#2b6cb0] transition-colors disabled:opacity-50"
                        placeholder="输入微调要求，如：换成下午5点开始，或换个地方..."
                        value={tweaks[message.id] || ''}
                        onChange={(e) => setTweaks((prev) => ({ ...prev, [message.id]: e.target.value }))}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') {
                            const matchedPoiIds = Array.from(
                              message.content.matchAll(/\[POI:([^\]:]+):[^\]]+\]/g)
                            ).map((match) => match[1])
                            const adjustment = tweaks[message.id] || ''
                            if (adjustment.trim() && onBuildAdjustedPuzzlePlan && matchedPoiIds.length > 0) {
                              onBuildAdjustedPuzzlePlan(matchedPoiIds, adjustment)
                              setTweaks((prev) => ({ ...prev, [message.id]: '' }))
                            }
                          }
                        }}
                      />
                      <button
                        type="button"
                        disabled={isDisabled || !(tweaks[message.id] || '').trim()}
                        className="px-4 py-2 border-2 border-[#2b6cb0] rounded-[12px] bg-[#2b6cb0] text-xs font-black text-[#fff] hover:scale-[1.02] active:scale-[0.98] active:translate-y-[1px] cursor-pointer transition-all disabled:opacity-50 disabled:pointer-events-none"
                        onClick={() => {
                          const matchedPoiIds = Array.from(
                            message.content.matchAll(/\[POI:([^\]:]+):[^\]]+\]/g)
                          ).map((match) => match[1])
                          const adjustment = tweaks[message.id] || ''
                          if (adjustment.trim() && onBuildAdjustedPuzzlePlan && matchedPoiIds.length > 0) {
                            onBuildAdjustedPuzzlePlan(matchedPoiIds, adjustment)
                            setTweaks((prev) => ({ ...prev, [message.id]: '' }))
                          }
                        }}
                      >
                        微调
                      </button>
                    </div>
                  </div>
                )}
              </div>
            </div>
          )
        })}
      </div>

      <div className="border-t-2 border-[#c4b89e]/60 bg-[#fff9e8] p-3">
        <div className="grid grid-cols-[1fr_auto] gap-2">
          <Input
            allowClear
            value={draft}
            disabled={isDisabled}
            placeholder="告诉 PlanPal 想去哪或怎么改..."
            onChange={(event) => onDraftChange(event.target.value)}
            onClear={() => onDraftChange('')}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                onSend()
              }
            }}
          />
          <Button
            type="primary"
            disabled={isDisabled || !draft.trim()}
            className="bg-[#ffcc00]! border-[#ffcc00]! text-[#725d42]! shadow-[0_4px_0_0_#dba90e]!"
            onClick={onSend}
          >
            发送
          </Button>
        </div>
      </div>
    </div>
  )
}
