import { Button, Card, Input } from 'animal-island-ui'
import type { ChangeEvent, KeyboardEvent, MouseEvent, ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import merchantPlaceholder from '../assets/hero.png'
import type { ChatMessage } from '../types/plan'

type ActionOption = NonNullable<ChatMessage['actionCard']>['options'][number]

function isContextualDraftOption(option: ActionOption) {
  const prefer = (option.planPatch as any)?.requirements?.prefer
  return Array.isArray(prefer) && prefer.includes('CONTEXT_READY')
}

type PlanPalChatColumnProps = {
  draft: string
  isDisabled?: boolean
  messages: ChatMessage[]
  onDraftChange: (value: string) => void
  onExecuteActionCardOption?: (messageId: string, option: ActionOption) => void
  onOpenMerchant?: (name: string) => void
  onBuildPuzzlePlan?: (poiIds: string[]) => void
  onBuildAdjustedPuzzlePlan?: (poiIds: string[], adjustmentText: string) => void
  onSend: (customText?: string) => void
  onSendStructuredPrompt?: (prompt: string, context?: { source?: string }) => void
}

const poiTagRegex = /\[POI[:：]([^:：\]]+)[:：]([^\]]+)\]/gi
const poiInlineRegex = /(\*\*.*?\*\*|\[POI[:：][^\]]+\])/gi

function detectMissingInfo(intent: any | null, prompt: string) {
  // 如果后端传了 intent，那么直接根据 intent 结构进行精确判断！
  if (intent) {
    // 判断时间是否缺失
    let missingTime = false
    if (!intent.startTime) {
      missingTime = true
    } else if (intent.startTime === '14:00') {
      // 默认 14:00。如果在原始 prompt 中没有任何时间关键词，则是真正缺失
      const lower = (intent.originalPrompt || prompt || '').toLowerCase()
      const hasTimeKeywords =
        lower.includes('点') ||
        lower.includes('分') ||
        lower.includes('时') ||
        lower.includes('am') ||
        lower.includes('pm') ||
        lower.includes('clock') ||
        lower.includes('：') ||
        lower.includes(':') ||
        lower.includes('下午') ||
        lower.includes('晚上') ||
        lower.includes('中午') ||
        lower.includes('上午') ||
        lower.includes('早上') ||
        lower.includes('夜里') ||
        lower.includes('凌晨')
      missingTime = !hasTimeKeywords
    }

    // 判断人数是否缺失
    let missingHeadcount = false
    if (intent.headcount <= 0) {
      missingHeadcount = true
    } else if (intent.headcount === 1) {
      // 默认 1。如果在原始 prompt 中没有任何人数关键词，则是真正缺失
      const lower = (intent.originalPrompt || prompt || '').toLowerCase()
      const hasHeadcountKeywords =
        lower.includes('人') ||
        lower.includes('位') ||
        lower.includes('独自') ||
        lower.includes('自己') ||
        lower.includes('情侣') ||
        lower.includes('老婆') ||
        lower.includes('老公') ||
        lower.includes('妻子') ||
        lower.includes('丈夫') ||
        lower.includes('孩子') ||
        lower.includes('娃') ||
        lower.includes('朋友') ||
        lower.includes('聚会') ||
        lower.includes('聚聚') ||
        lower.includes('战友') ||
        lower.includes('闺蜜') ||
        lower.includes('同学') ||
        lower.includes('同事') ||
        lower.includes('团建') ||
        lower.includes('约会')
      missingHeadcount = !hasHeadcountKeywords
    }

    return { missingTime, missingHeadcount }
  }

  // 兜底（如果后端没传 intent，退回到原有的关键词规则判断）
  if (!prompt) {
    return { missingTime: true, missingHeadcount: true }
  }
  const lower = prompt.toLowerCase()

  const hasTime =
    lower.includes('点') ||
    lower.includes('分') ||
    lower.includes('时') ||
    lower.includes('am') ||
    lower.includes('pm') ||
    lower.includes('clock') ||
    lower.includes('：') ||
    lower.includes(':') ||
    lower.includes('下午') ||
    lower.includes('晚上') ||
    lower.includes('中午') ||
    lower.includes('上午') ||
    lower.includes('早上') ||
    lower.includes('夜里') ||
    lower.includes('凌晨')

  const hasHeadcount =
    lower.includes('人') ||
    lower.includes('位') ||
    lower.includes('独自') ||
    lower.includes('自己') ||
    lower.includes('情侣') ||
    lower.includes('老婆') ||
    lower.includes('老公') ||
    lower.includes('妻子') ||
    lower.includes('丈夫') ||
    lower.includes('孩子') ||
    lower.includes('娃') ||
    lower.includes('朋友') ||
    lower.includes('聚会') ||
    lower.includes('聚聚') ||
    lower.includes('战友') ||
    lower.includes('闺蜜') ||
    lower.includes('同学') ||
    lower.includes('同事') ||
    lower.includes('团建') ||
    lower.includes('约会')

  return {
    missingTime: !hasTime,
    missingHeadcount: !hasHeadcount,
  }
}

export function PlanPalChatColumn({
  draft,
  isDisabled = false,
  messages,
  onDraftChange,
  onExecuteActionCardOption,
  onOpenMerchant,
  onBuildPuzzlePlan,
  onBuildAdjustedPuzzlePlan,
  onSend,
  onSendStructuredPrompt,
}: PlanPalChatColumnProps) {
  const [tweaks, setTweaks] = useState<Record<string, string>>({})
  const scrollRef = useRef<HTMLDivElement>(null)
  const messageRenderKey = messages
    .map((message) => {
      const lastActivity = message.activity?.[message.activity.length - 1]
      return `${message.id}:${message.content.length}:${message.isLoading ? 'loading' : 'done'}:${message.activity?.length || 0}:${lastActivity?.status || ''}`
    })
    .join('|')

  useEffect(() => {
    scrollRef.current?.scrollTo({
      top: scrollRef.current.scrollHeight,
      behavior: 'smooth',
    })
  }, [messageRenderKey])
  const [clarifyTime, setClarifyTime] = useState<Record<string, string>>({})
  const [clarifyCount, setClarifyCount] = useState<Record<string, number>>({})
  const [clarifyCustom, setClarifyCustom] = useState<Record<string, string>>({})

  function extractPoiIds(content: string) {
    return Array.from(content.matchAll(poiTagRegex)).map((match) => match[1].trim())
  }

  function extractOptions(content: string) {
    const options: Array<{ title: string; poiIds: string[]; rawText: string }> = []
    const optionSplitRegex = /(?:^|\n)(?=[^\n]*(?:方案[一二三四五六七八九十1234567890\d]|Option\s*[1-9]))/gi
    const segments = content.split(optionSplitRegex)
    
    segments.forEach((seg) => {
      const trimmed = seg.trim()
      if (!trimmed) return
      
      const match = trimmed.match(/(?:方案[一二三四五六七八九十1234567890\d]|Option\s*[1-9])[:：\s]*([^\n]*)/i)
      if (match) {
        const optionKeyword = trimmed.match(/(方案[一二三四五六七八九十1234567890\d]|Option\s*[1-9])/i)?.[1] || '方案'
        const titleDescription = match[1].trim()
        const title = `${optionKeyword}${titleDescription ? `: ${titleDescription}` : ''}`
        const poiIds = Array.from(seg.matchAll(poiTagRegex)).map((m) => m[1].trim())
        if (poiIds.length > 0) {
          options.push({ title, poiIds, rawText: seg })
        }
      }
    })
    
    return { options }
  }

  function submitInlinePrompt(messageId: string, source = 'chat-card') {
    const custom = tweaks[messageId] || ''
    if (!custom.trim()) return
    onSendStructuredPrompt?.(custom, { source })
    setTweaks((prev) => ({ ...prev, [messageId]: '' }))
  }

  function parseAndRenderContent(content: string) {
    if (!content) return ''

    function parseInline(text: string): ReactNode[] {
      const parts = text.split(poiInlineRegex)
      return parts.map((part, index) => {
        if (part.startsWith('**') && part.endsWith('**')) {
          return (
            <strong key={index} className="font-extrabold text-[#794f27]">
              {parseInline(part.slice(2, -2))}
            </strong>
          )
        }

        if (/^\[POI[:：]/i.test(part) && /\]$/.test(part)) {
          const poiMatch = /\[POI[:：]([^:：\]]+)[:：]([^\]]+)\]/i.exec(part)
          if (poiMatch) {
            const poiId = poiMatch[1].trim()
            const poiName = poiMatch[2].trim()
            return (
              <span
                key={`${poiId}-${index}`}
                className="inline-flex items-center gap-1 mx-1 px-2.5 py-1 rounded-[12px] border border-[#c4b89e] bg-[#fffdf5] text-[#794f27] text-xs font-black shadow-[0_2px_0_0_#d4c9b4] hover:bg-[#ffeea0] active:translate-y-[1px] active:shadow-none transition-all cursor-pointer select-none"
                onClick={() => onOpenMerchant?.(poiName)}
                title={`点击查看 ${poiName} 详情`}
              >
                📍 {poiName}
              </span>
            )
          }
        }

        return part
      })
    }

    const lines = content.split('\n')
    const elements: ReactNode[] = []
    let currentList: ReactNode[] = []

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim()

      if (line === '---' || line === '***') {
        if (currentList.length > 0) {
          elements.push(<ul key={`list-${i}`} className="list-disc pl-5 my-2 space-y-1.5">{...currentList}</ul>)
          currentList = []
        }
        elements.push(<hr key={`hr-${i}`} className="my-3.5 border-t-2 border-[#c4b89e]/30" />)
        continue
      }

      if (line.startsWith('### ')) {
        if (currentList.length > 0) {
          elements.push(<ul key={`list-${i}`} className="list-disc pl-5 my-2 space-y-1.5">{...currentList}</ul>)
          currentList = []
        }
        elements.push(
          <h3 key={`h3-${i}`} className="text-[#794f27] text-base font-black mt-4 mb-2 flex items-center gap-1.5">
            {parseInline(line.substring(4).trim())}
          </h3>,
        )
        continue
      }

      if (line.startsWith('#### ')) {
        if (currentList.length > 0) {
          elements.push(<ul key={`list-${i}`} className="list-disc pl-5 my-2 space-y-1.5">{...currentList}</ul>)
          currentList = []
        }
        elements.push(
          <h4 key={`h4-${i}`} className="text-[#794f27] text-sm font-black mt-3 mb-1.5">
            {parseInline(line.substring(5).trim())}
          </h4>,
        )
        continue
      }

      if (line.startsWith('- ') || line.startsWith('* ') || line.startsWith('•')) {
        currentList.push(
          <li key={`li-${i}-${currentList.length}`} className="text-sm font-bold leading-relaxed text-[#725d42]">
            {parseInline(line.substring(2).trim())}
          </li>,
        )
        continue
      }

      if (line === '') {
        if (currentList.length > 0) {
          elements.push(<ul key={`list-${i}`} className="list-disc pl-5 my-2 space-y-1.5">{...currentList}</ul>)
          currentList = []
        }
        elements.push(<div key={`space-${i}`} className="h-2" />)
        continue
      }

      if (currentList.length > 0) {
        elements.push(<ul key={`list-${i}`} className="list-disc pl-5 my-2 space-y-1.5">{...currentList}</ul>)
        currentList = []
      }

      elements.push(
        <p key={`p-${i}`} className="my-1.5 text-sm font-bold leading-relaxed text-[#725d42]">
          {parseInline(lines[i])}
        </p>,
      )
    }

    if (currentList.length > 0) {
      elements.push(<ul key="list-end" className="list-disc pl-5 my-2 space-y-1.5">{...currentList}</ul>)
    }

    return <div className="space-y-0.5">{elements}</div>
  }

  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-hidden bg-[#f7f3df]">
      <div ref={scrollRef} className="flex-1 min-h-0 overflow-y-auto custom-scrollbar p-4 pb-3 space-y-3">
        {messages.length === 0 && (
          <Card className="rounded-[24px]! border-2! border-[#c4b89e]! bg-[#fff9e8]! p-4! text-[#725d42]! shadow-[0_4px_0_0_#d4c9b4]! hover:!translate-y-0">
            <span className="inline-flex rounded-full bg-[#e6f9f6] px-3 py-1 text-[12px] font-black text-[#11a89b]">
              PlanPal
            </span>
            <h3 className="m-0 mt-2 text-[#794f27] text-lg font-black">与 PlanPal 对话</h3>
            <p className="m-0 mt-1 text-sm font-semibold leading-relaxed">
              例如“吃饭太远了，换个近一点的地方”或“下午那个活动太累了，轻一点”。
            </p>
          </Card>
        )}

        {messages.map((message, index) => {
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
                {message.isLoading ? (
                  <div className="flex flex-col gap-1.5 min-w-[200px]">
                    {message.content ? (
                      <div className="text-sm font-bold leading-relaxed text-[#725d42]">
                        {isPlanPal ? parseAndRenderContent(message.content) : message.content}
                      </div>
                    ) : (
                      <div className="flex items-center gap-2 animate-pulse">
                        <span className="inline-flex rounded-full bg-[#e6f9f6] px-2 py-0.5 text-[10px] font-black text-[#11a89b]">
                          PlanPal
                        </span>
                        <span className="text-[11px] font-black text-[#794f27]/75">正在思考中...</span>
                      </div>
                    )}
                    <div className="space-y-1.5 pt-1.5 animate-pulse">
                      <div className="h-3 w-11/12 rounded bg-[#e8e2cf] animate-shimmer relative overflow-hidden" 
                           style={{
                             background: 'linear-gradient(90deg, #e8e2cf 25%, #f2ebd9 50%, #e8e2cf 75%)',
                             backgroundSize: '200% 100%',
                           }}
                      ></div>
                      <div className="h-3 w-4/5 rounded bg-[#e8e2cf] animate-shimmer relative overflow-hidden"
                           style={{
                             background: 'linear-gradient(90deg, #e8e2cf 25%, #f2ebd9 50%, #e8e2cf 75%)',
                             backgroundSize: '200% 100%',
                           }}
                      ></div>
                    </div>
                  </div>
                ) : isPlanPal ? (
                  parseAndRenderContent(message.content)
                ) : (
                  message.content
                )}

                {isPlanPal && message.activity && message.activity.length > 0 && (
                  <div className="mt-3 rounded-[16px] border-2 border-[#c4b89e]/50 bg-[#fcfaf2] p-3 shadow-inner">
                    <div className="flex items-center justify-between gap-3 text-[11px] font-black uppercase tracking-wider text-[#794f27]">
                      <span>PlanPal 正在做什么</span>
                      <span className="shrink-0 rounded-full bg-[#efe7d2] px-2 py-0.5 text-[10px] text-[#8a7657]">
                        {message.activity.some((item) => item.status === 'running') ? '进行中' : '已完成'}
                      </span>
                    </div>
                    <div className="mt-2 flex max-h-[180px] flex-col gap-2 overflow-y-auto pr-1 custom-scrollbar">
                      {message.activity.map((item) => (
                        <div key={item.id} className="grid grid-cols-[18px_1fr] gap-2 text-xs">
                          <span
                            className={`mt-0.5 h-[14px] w-[14px] rounded-full border-2 ${
                              item.status === 'running'
                                ? 'border-[#11a89b] bg-[#e6f9f6] animate-pulse'
                                : item.status === 'error'
                                ? 'border-[#c2410c] bg-[#ffedd5]'
                                : 'border-[#8fbf45] bg-[#eef7df]'
                            }`}
                          />
                          <div className="min-w-0">
                            <div className="font-black text-[#725d42]">{item.label}</div>
                            {item.detail && (
                              <div className="mt-0.5 break-words text-[11px] font-bold leading-snug text-[#8a7657]">
                                {item.detail}
                              </div>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {(() => {
                  const isClarifyMessage = isPlanPal && (
                    message.content.includes('请问您计划在') ||
                    message.content.includes('什么时间段') ||
                    message.content.includes('请补充') ||
                    message.content.includes('请提供时间范围') ||
                    message.content.includes('为了给您拼出更准确的行程') ||
                    message.content.includes('继续生成行程拼图')
                  )
                  if (!isClarifyMessage) return null

                  let prevUserPrompt = ''
                  for (let i = index - 1; i >= 0; i--) {
                    if (messages[i].role === 'user') {
                      prevUserPrompt = messages[i].content
                      break
                    }
                  }

                  const { missingTime, missingHeadcount } = detectMissingInfo(message.intent, prevUserPrompt)
                  let showTime = missingTime
                  let showHeadcount = missingHeadcount
                  if (!showTime && !showHeadcount) {
                    showTime = true
                    showHeadcount = true
                  }

                  let sectionIndex = 1
                  const timeNum = showTime ? sectionIndex++ : 0
                  const headcountNum = showHeadcount ? sectionIndex++ : 0
                  const customNum = sectionIndex++
                  const selectedTime = clarifyTime[message.id] || ''
                  const selectedCount = clarifyCount[message.id] || 2
                  const canSubmitClarification = (!showTime || selectedTime) && (!showHeadcount || selectedCount)

                  return (
                    <div className="mt-3.5 pt-3.5 border-t-2 border-[#c4b89e]/30 flex flex-col gap-3.5 bg-[#fcfaf2]/90 border-2 border-[#c4b89e]/60 rounded-[18px] p-4 shadow-inner">
                      <span className="text-xs font-black text-[#794f27]/90 uppercase tracking-wider">
                        请补充下面内容：
                      </span>

                      {showTime && (
                        <div className="flex flex-col gap-1.5">
                          <span className="text-xs font-black text-[#794f27]">{timeNum}. 出行时间段</span>
                          <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
                            {[
                              { label: '下午见面', value: '下午见面，具体时间再确认' },
                              { label: '上午见面', value: '上午见面，具体时间再确认' },
                              { label: '晚上见面', value: '晚上见面，具体时间再确认' },
                            ].map((opt) => {
                              const isSelected = selectedTime === opt.value
                              return (
                                <button
                                  key={opt.value}
                                  type="button"
                                  className={`px-3 py-2 text-xs font-bold rounded-[12px] border-2 cursor-pointer transition-all duration-150 ${
                                    isSelected
                                      ? 'border-[#2b6cb0]! bg-[#2b6cb0]! text-white! shadow-[0_2px_0_0_#1a365d]!'
                                      : 'border-[#c4b89e]! bg-white! text-[#725d42]! hover:bg-[#ffeea0]!'
                                  }`}
                                  onClick={() => setClarifyTime((prev) => ({ ...prev, [message.id]: opt.value }))}
                                >
                                  {opt.label}
                                </button>
                              )
                            })}
                          </div>
                        </div>
                      )}

                      {showHeadcount && (
                        <div className="flex flex-col gap-1.5">
                          <span className="text-xs font-black text-[#794f27]">{headcountNum}. 出行人数</span>
                          <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                            {[
                              { label: '1 人', value: 1 },
                              { label: '2 人', value: 2 },
                              { label: '3 人', value: 3 },
                              { label: '4 人', value: 4 },
                            ].map((opt) => {
                              const isSelected = selectedCount === opt.value
                              return (
                                <button
                                  key={opt.value}
                                  type="button"
                                  className={`px-3 py-2 text-xs font-bold rounded-[12px] border-2 cursor-pointer transition-all duration-150 ${
                                    isSelected
                                      ? 'border-[#2b6cb0]! bg-[#2b6cb0]! text-white! shadow-[0_2px_0_0_#1a365d]!'
                                      : 'border-[#c4b89e]! bg-white! text-[#725d42]! hover:bg-[#ffeea0]!'
                                  }`}
                                  onClick={() => setClarifyCount((prev) => ({ ...prev, [message.id]: opt.value }))}
                                >
                                  {opt.label}
                                </button>
                              )
                            })}
                          </div>
                        </div>
                      )}

                      <div className="flex flex-col gap-1.5">
                        <span className="text-xs font-black text-[#794f27]">{customNum}. 其他要求</span>
                        <input
                          type="text"
                          disabled={isDisabled}
                          className="w-full px-3.5 py-2 border-2 border-[#c4b89e] rounded-[12px] bg-[#fdfcf7] text-xs font-bold text-[#725d42] placeholder-[#9f927d]/80 outline-none focus:border-[#2b6cb0] transition-colors disabled:opacity-50"
                          placeholder="例如：室内一点，别太赶"
                          value={clarifyCustom[message.id] || ''}
                          onChange={(e) => setClarifyCustom((prev) => ({ ...prev, [message.id]: e.target.value }))}
                        />
                      </div>

                      <Button
                        type="primary"
                        disabled={isDisabled || !canSubmitClarification}
                        className="w-full bg-[#ffcc00]! border-[#ffcc00]! text-[#725d42]! shadow-[0_4px_0_0_#dba90e]! hover:scale-[1.01] active:scale-[0.99] active:translate-y-[1px] active:shadow-none transition-all duration-150 font-black text-sm py-2.5! h-auto! rounded-[12px]!"
                        onClick={() => {
                          if (!canSubmitClarification) return
                          let parts: string[] = []
                          if (showTime) {
                            parts.push(`我计划在${selectedTime}出行`)
                          }
                          if (showHeadcount) {
                            parts.push(`总共 ${selectedCount} 个人`)
                          }
                          let combinedPrompt = parts.join('，')
                          if (combinedPrompt) {
                            combinedPrompt += '。'
                          }
                          const custom = clarifyCustom[message.id] || ''
                          if (custom.trim()) {
                            combinedPrompt += `特殊要求：${custom}。`
                          }
                          onDraftChange('')
                          onSend(combinedPrompt)
                        }}
                      >
                        {showTime && !selectedTime ? '先告诉 PlanPal 时间' : '继续让 PlanPal 安排'}
                      </Button>
                    </div>
                  )
                })()}

                {isPlanPal && message.actionCard && (
                  <div className="mt-3.5 pt-3.5 border-t-2 border-[#c4b89e]/30 flex flex-col gap-3.5 bg-[#fcfaf2]/90 border-2 border-[#c4b89e]/60 rounded-[18px] p-4 shadow-inner">
                    <div className="flex flex-col gap-1">
                      <span className="text-[11px] font-black text-[#725d42]/70 uppercase tracking-wider">
                        推荐操作
                      </span>
                      <span className="text-base font-black text-[#794f27]">{message.actionCard.title}</span>
                      <p className="m-0 text-xs font-semibold text-[#725d42] leading-relaxed">
                        {message.actionCard.description}
                      </p>
                    </div>

                    <div className="flex flex-col gap-2.5">
                      {message.actionCard.options.map((option) => {
                        const preview = option.poiPreview
                        if (preview) {
                          const tags = (preview.tags || []).slice(0, 3)
                          const actionLabel = isContextualDraftOption(option) ? '先放进草稿' : '选择这个'
                          const meta = [
                            preview.category,
                            Number.isFinite(preview.distanceKm) ? `${preview.distanceKm.toFixed(1)}km` : '',
                            preview.businessHours || preview.address,
                          ].filter(Boolean).join(' · ')
                          return (
                            <div
                              key={option.id}
                              role="button"
                              tabIndex={0}
                              className="grid grid-cols-[76px_1fr] gap-3 rounded-[14px] border-2 border-[#c4b89e] bg-[#fffdf5] p-2.5 shadow-[0_3px_0_0_#d4c9b4] cursor-pointer hover:bg-[#fff7d8] active:translate-y-[1px] active:shadow-none transition-all"
                              onClick={() => onOpenMerchant?.(preview.name)}
                              onKeyDown={(event) => {
                                if (event.key === 'Enter' || event.key === ' ') onOpenMerchant?.(preview.name)
                              }}
                            >
                              <img
                                src={merchantPlaceholder}
                                alt=""
                                className="w-[76px] h-[76px] rounded-[10px] object-cover border border-[#e7ddc8]"
                              />
                              <div className="min-w-0 flex flex-col gap-1.5">
                                <div className="flex items-start justify-between gap-2">
                                  <span className="text-sm font-black text-[#794f27] leading-tight">{preview.name}</span>
                                  <span className="shrink-0 rounded-full bg-[#eef7df] px-2 py-0.5 text-[10px] font-black text-[#426a15]">
                                    {preview.source || 'poi'}
                                  </span>
                                </div>
                                <p className="m-0 text-[11px] font-bold text-[#725d42] leading-snug line-clamp-2">{meta}</p>
                                <div className="flex flex-wrap gap-1">
                                  {tags.map((tag) => (
                                    <span key={tag} className="rounded-full bg-[#fff3c4] px-2 py-0.5 text-[10px] font-black text-[#725d42]">
                                      {tag}
                                    </span>
                                  ))}
                                </div>
                                <Button
                                  type="primary"
                                  disabled={isDisabled}
                                  className="mt-1 min-h-[28px]! px-3! text-[11px]! w-fit"
                                  onClick={(event: MouseEvent<HTMLButtonElement>) => {
                                    event.stopPropagation()
                                    onExecuteActionCardOption?.(message.id, option)
                                  }}
                                >
                                  {actionLabel}
                                </Button>
                              </div>
                            </div>
                          )
                        }

                        return (
                          <button
                            key={option.id}
                            type="button"
                            disabled={isDisabled}
                            className="w-full text-left bg-[#2b6cb0] hover:bg-[#23588f] disabled:bg-[#a3c3e6] text-[#fff] border-2 border-[#1e4d80] rounded-[14px] px-4 py-3 shadow-[0_4px_0_0_#1a365d] active:translate-y-[2px] active:shadow-[0_2px_0_0_#1a365d] transition-all flex flex-col gap-1 cursor-pointer disabled:cursor-not-allowed select-none"
                            onClick={() => onExecuteActionCardOption?.(message.id, option)}
                          >
                            <strong className="text-sm font-black tracking-wide leading-tight">{option.label}</strong>
                            {option.description && (
                              <span className="text-[11px] font-semibold text-[#e2eeff] leading-relaxed block">
                                {option.description}
                              </span>
                            )}
                          </button>
                        )
                      })}
                    </div>

                    {message.actionCard.allowCustomInput && (
                      <div className="flex flex-col gap-1.5">
                        <span className="text-xs font-black text-[#794f27]">补充您的微调要求</span>
                        <div className="flex gap-2">
                          <input
                            type="text"
                            disabled={isDisabled}
                            className="flex-1 px-3.5 py-2 border-2 border-[#c4b89e] rounded-[12px] bg-[#fdfcf7] text-xs font-bold text-[#725d42] placeholder-[#9f927d]/80 outline-none focus:border-[#2b6cb0] transition-colors disabled:opacity-50"
                            placeholder={message.actionCard.inputPlaceholder || '例如：餐厅别换，尽量早点结束'}
                            value={tweaks[message.id] || ''}
                            onChange={(e) => setTweaks((prev) => ({ ...prev, [message.id]: e.target.value }))}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter') {
                                submitInlinePrompt(message.id, 'action-card-custom')
                              }
                            }}
                          />
                          <button
                            type="button"
                            disabled={isDisabled || !(tweaks[message.id] || '').trim()}
                            className="px-4 py-2 border-2 border-[#2b6cb0] rounded-[12px] bg-[#2b6cb0] text-xs font-black text-[#fff] hover:scale-[1.02] active:scale-[0.98] active:translate-y-[1px] cursor-pointer transition-all disabled:opacity-50 disabled:pointer-events-none"
                            onClick={() => submitInlinePrompt(message.id, 'action-card-custom')}
                          >
                            微调
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                )}

                {hasCta && (() => {
                  const { options } = extractOptions(message.content)
                  if (options.length > 1) {
                    return (
                      <div className="mt-3.5 pt-3.5 border-t-2 border-[#c4b89e]/30 flex flex-col gap-2.5 bg-[#fcfaf2]/80 border-2 border-[#c4b89e]/60 rounded-[16px] p-3 shadow-inner">
                        <div className="flex items-center justify-between">
                          <span className="text-[11px] font-black text-[#725d42]/70 uppercase tracking-wider">
                            请选择最满意的出行方案并构建：
                          </span>
                        </div>

                        <div className="flex flex-col gap-2">
                          {options.map((opt, oIdx) => (
                            <Button
                              key={oIdx}
                              type="primary"
                              className="w-full bg-[#2b6cb0]! border-[#2b6cb0]! text-[#fff]! shadow-[0_4px_0_0_#1a365d]! hover:scale-[1.01] active:scale-[0.99] active:translate-y-[1px] active:shadow-none transition-all duration-150 font-black text-sm py-2.5! h-auto! rounded-[12px]! flex justify-center items-center gap-1.5"
                              onClick={() => {
                                if (onBuildPuzzlePlan && opt.poiIds.length > 0) {
                                  onBuildPuzzlePlan(opt.poiIds)
                                }
                              }}
                            >
                              🗺️ 同意并构建「{opt.title}」
                            </Button>
                          ))}
                        </div>

                        <div className="border-t border-[#c4b89e]/30 my-0.5"></div>

                        <div className="flex gap-2">
                          <input
                            type="text"
                            disabled={isDisabled}
                            className="flex-1 px-3.5 py-2 border-2 border-[#c4b89e] rounded-[12px] bg-[#fdfcf7] text-xs font-bold text-[#725d42] placeholder-[#9f927d]/80 outline-none focus:border-[#2b6cb0] transition-colors disabled:opacity-50"
                            placeholder="输入微调要求，例如：换成下午 5 点开始"
                            value={tweaks[message.id] || ''}
                            onChange={(e) => setTweaks((prev) => ({ ...prev, [message.id]: e.target.value }))}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter') {
                                const matchedPoiIds = extractPoiIds(message.content)
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
                              const matchedPoiIds = extractPoiIds(message.content)
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
                    )
                  }

                  // Default single option:
                  return (
                    <div className="mt-3.5 pt-3.5 border-t-2 border-[#c4b89e]/30 flex flex-col gap-2.5 bg-[#fcfaf2]/80 border-2 border-[#c4b89e]/60 rounded-[16px] p-3 shadow-inner">
                      <div className="flex items-center justify-between">
                        <span className="text-[11px] font-black text-[#725d42]/70 uppercase tracking-wider">
                          推荐操作
                        </span>
                      </div>

                      <Button
                        type="primary"
                        className="w-full bg-[#2b6cb0]! border-[#2b6cb0]! text-[#fff]! shadow-[0_4px_0_0_#1a365d]! hover:scale-[1.01] active:scale-[0.99] active:translate-y-[1px] active:shadow-none transition-all duration-150 font-black text-sm py-2.5! h-auto! rounded-[12px]! flex justify-center items-center gap-1.5"
                        onClick={() => {
                          const matchedPoiIds = extractPoiIds(message.content)
                          if (onBuildPuzzlePlan && matchedPoiIds.length > 0) {
                            onBuildPuzzlePlan(matchedPoiIds)
                          }
                        }}
                      >
                        同意并构建行程
                      </Button>

                      <div className="border-t border-[#c4b89e]/30 my-0.5"></div>

                      <div className="flex gap-2">
                        <input
                          type="text"
                          disabled={isDisabled}
                          className="flex-1 px-3.5 py-2 border-2 border-[#c4b89e] rounded-[12px] bg-[#fdfcf7] text-xs font-bold text-[#725d42] placeholder-[#9f927d]/80 outline-none focus:border-[#2b6cb0] transition-colors disabled:opacity-50"
                          placeholder="输入微调要求，例如：换成下午 5 点开始"
                          value={tweaks[message.id] || ''}
                          onChange={(e) => setTweaks((prev) => ({ ...prev, [message.id]: e.target.value }))}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') {
                              const matchedPoiIds = extractPoiIds(message.content)
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
                            const matchedPoiIds = extractPoiIds(message.content)
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
                  )
                })()}
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
            onChange={(event: ChangeEvent<HTMLInputElement>) => onDraftChange(event.target.value)}
            onClear={() => onDraftChange('')}
            onKeyDown={(event: KeyboardEvent<HTMLInputElement>) => {
              if (event.key === 'Enter') {
                onSend()
              }
            }}
          />
          <Button
            type="primary"
            disabled={isDisabled || !draft.trim()}
            className="bg-[#ffcc00]! border-[#ffcc00]! text-[#725d42]! shadow-[0_4px_0_0_#dba90e]!"
            onClick={() => onSend()}
          >
            发送
          </Button>
        </div>
      </div>
    </div>
  )
}
