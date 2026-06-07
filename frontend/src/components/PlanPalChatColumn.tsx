import { Button, Card, Input } from 'animal-island-ui'
import type { ChangeEvent, KeyboardEvent, ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import merchantPlaceholder from '../assets/hero.png'
import type { ChatMessage } from '../types/plan'

type ActionOption = NonNullable<ChatMessage['actionCard']>['options'][number]

type AdjustmentReceipt = {
  title: string
  detail: string
  chips: string[]
  secondary?: string
}

type CandidateControlIntent = {
  label: string
  detail: string
  chip?: string
}

function isMovieScreeningOption(option: ActionOption, cardKind?: string | null) {
  return option.optionKind === 'MOVIE_SCREENING' || cardKind === 'MOVIE_SCREENING'
}

const preferenceLabels: Record<string, string> = {
  CHILD_FRIENDLY: '适合带娃',
  CONTEXT_READY: '信息已补齐',
  INDOOR: '室内优先',
  NEARBY: '距离近一点',
  QUIET: '安静一点',
  RELAXED: '轻松节奏',
}

function extractMetadata(content: string, key: string) {
  const marker = `${key}:`
  const start = content.indexOf(marker)
  if (start < 0) return null
  const rest = content.slice(start + marker.length)
  const nextKey = rest.search(/\/[A-Z_]+:/)
  return (nextKey >= 0 ? rest.slice(0, nextKey) : rest).trim().replace(/\/$/, '') || null
}

function extractTimeList(content: string, key: string) {
  const match = new RegExp(`${key}:([0-9:|]+)`).exec(content)
  return match?.[1]
    .split('|')
    .map((time) => time.trim())
    .filter(Boolean) || []
}

function readablePreference(value: string) {
  const normalized = value.trim()
  if (!normalized || /^[A-Z_]+:.+/.test(normalized)) return null
  return preferenceLabels[normalized] || null
}

function extractReadablePreferences(content: string) {
  const preferMatch = /偏好\s+([^。]+?)(?:，避开|，保留|。|$)/.exec(content)
  if (!preferMatch) return []
  return preferMatch[1]
    .split('/')
    .map(readablePreference)
    .filter((value): value is string => Boolean(value))
}

function operationDetail(content: string) {
  if (content.includes('增加新节点')) return '已加入一个新安排'
  if (content.includes('替换目标节点')) return '已替换行程中的一个安排'
  if (content.includes('删除目标节点')) return '已移除一个安排'
  if (content.includes('调整时间')) return '已调整行程时间'
  if (content.includes('压缩节奏')) return '已把行程排得更紧凑'
  if (content.includes('放松节奏')) return '已把行程调得更轻松'
  return '已更新你的行程偏好'
}

function parseAdjustmentReceipt(content: string): AdjustmentReceipt | null {
  if (!content.includes('计划已微调') && !/[A-Z_]+:/.test(content)) return null
  if (!/(SELECTED_POI|MOVIE_TITLE|MOVIE_TIME|MOVIE_SHOWTIMES|偏好)/.test(content)) return null

  const movieTitle = extractMetadata(content, 'MOVIE_TITLE')
  const movieTime = extractTimeList(content, 'MOVIE_TIME')[0] || extractMetadata(content, 'MOVIE_TIME')
  const showtimes = extractTimeList(content, 'MOVIE_SHOWTIMES')

  if (movieTitle) {
    const otherShowtimes = showtimes.filter((time) => time !== movieTime)
    return {
      title: '计划已微调',
      detail: movieTime ? `已加入「${movieTitle}」${movieTime} 场次` : `已加入「${movieTitle}」`,
      chips: movieTime ? [`${movieTime} 场次`] : [],
      secondary: otherShowtimes.length ? `其他场次：${otherShowtimes.join(' / ')}` : undefined,
    }
  }

  const chips = extractReadablePreferences(content)
  return {
    title: '计划已微调',
    detail: operationDetail(content),
    chips,
  }
}

function candidateKindLabel(cardKind?: string | null) {
  if (cardKind === 'MOVIE_SCREENING') return '电影场次'
  if (cardKind === 'POI') return '推荐地点'
  return '推荐候选'
}

function markerValue(content: string, key: string) {
  const match = new RegExp(`${key}=([^\\s]+)`).exec(content)
  return match?.[1]?.trim() || null
}

function countDelimited(value: string | null) {
  if (!value) return 0
  return value.split(',').map((item) => item.trim()).filter(Boolean).length
}

function parseCandidateControlIntent(content: string): CandidateControlIntent | null {
  const marker = content.includes('[REFRESH_CANDIDATES]')
    ? 'REFRESH_CANDIDATES'
    : content.includes('[REFINE_CANDIDATES]')
      ? 'REFINE_CANDIDATES'
      : null
  if (!marker) return null

  const kind = candidateKindLabel(markerValue(content, 'cardKind'))
  const excludedCount = Math.max(
    countDelimited(markerValue(content, 'excludeOptionIds')),
    countDelimited(markerValue(content, 'excludeScreeningIds')),
    countDelimited(markerValue(content, 'excludePoiIds')),
  )
  const requirement = content
    .replace(/\[(REFRESH|REFINE)_CANDIDATES\]/g, '')
    .replace(/\b(cardKind|excludeOptionIds|excludePoiIds|excludeScreeningIds)=[^\s]+/g, '')
    .trim()

  return {
    label: marker === 'REFRESH_CANDIDATES' ? `换一批${kind}` : `重新筛选${kind}`,
    detail: requirement ? `要求：${requirement}` : '已避开刚才展示过的候选',
    chip: excludedCount > 0 ? `避开 ${excludedCount} 个已展示项` : undefined,
  }
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
  onSelectPlanVariant?: (planId: string) => void
  onSend: (customText?: string) => void
  onSendStructuredPrompt?: (prompt: string, context?: { source?: string; userMessage?: string }) => void
}
const poiInlineRegex = /(\*\*.*?\*\*|\[POI[:：锛歖][^\]]+\])/gi

function ThinkingDots() {
  return (
    <span className="inline-flex items-center gap-1" aria-hidden="true">
      <span className="h-1.5 w-1.5 rounded-full bg-[#11a89b] animate-bounce" />
      <span className="h-1.5 w-1.5 rounded-full bg-[#11a89b] animate-bounce [animation-delay:120ms]" />
      <span className="h-1.5 w-1.5 rounded-full bg-[#11a89b] animate-bounce [animation-delay:240ms]" />
    </span>
  )
}

function StreamingContent({
  content,
  active,
  renderContent,
}: {
  content: string
  active: boolean
  renderContent: (value: string) => ReactNode
}) {
  const [visible, setVisible] = useState('')

  useEffect(() => {
    if (!active) {
      return
    }

    let index = 0
    const chunkSize = content.length > 260 ? 5 : content.length > 120 ? 3 : 2
    const timer = window.setInterval(() => {
      index = Math.min(content.length, index + chunkSize)
      setVisible(content.slice(0, index))
      if (index >= content.length) {
        window.clearInterval(timer)
      }
    }, 28)

    return () => window.clearInterval(timer)
  }, [active, content])

  return (
    <>
      {renderContent(active ? visible : content)}
      {active && visible.length < content.length && (
        <span className="ml-1 inline-block h-4 w-1 translate-y-0.5 rounded-full bg-[#11a89b]/80 animate-pulse" />
      )}
    </>
  )
}

function CompactActivityMessage({ activity }: { activity: NonNullable<ChatMessage['activity']> }) {
  const latest = activity[activity.length - 1]
  const running = activity.some((item) => item.status === 'running')
  const statusText = running ? latest?.label || '正在处理' : '处理完成'

  return (
    <div className="max-w-[92%] text-[#725d42]">
      <div
        role="status"
        aria-label={running ? 'PlanPal 正在处理' : 'PlanPal 已完成处理'}
        className="inline-flex max-w-full items-center gap-2 rounded-full border border-[#c4b89e]/70 bg-[#fff9e8]/95 px-3 py-2 text-xs font-black shadow-[0_2px_0_0_#d4c9b4]"
      >
        {running ? (
          <span className="relative flex h-3 w-3 shrink-0">
            <span className="absolute inline-flex h-full w-full rounded-full bg-[#11a89b]/40 animate-ping" />
            <span className="relative inline-flex h-3 w-3 rounded-full bg-[#11a89b]" />
          </span>
        ) : (
          <span className="h-3 w-3 shrink-0 rounded-full border-2 border-[#6fba2c] bg-[#eef7df]" />
        )}
        <span className="min-w-0 truncate text-[#794f27]">{statusText}</span>
        {running && <ThinkingDots />}
        <span className="shrink-0 rounded-full bg-[#efe7d2] px-2 py-0.5 text-[10px] text-[#8a7657]">
          {activity.length} 步
        </span>
      </div>

      <details className="mt-1.5 max-w-[360px] rounded-[14px] border border-[#c4b89e]/45 bg-[#fcfaf2]/90 px-3 py-2">
        <summary className="cursor-pointer list-none text-[11px] font-black text-[#8a7657]">
          查看处理细节
        </summary>
        <div className="mt-2 flex max-h-[150px] flex-col gap-1.5 overflow-y-auto pr-1 custom-scrollbar">
          {activity.map((item) => (
            <div key={item.id} className="grid grid-cols-[14px_1fr] gap-2 text-xs">
              <span
                className={`mt-1 h-2.5 w-2.5 rounded-full ${
                  item.status === 'running'
                    ? 'bg-[#11a89b] animate-pulse'
                    : item.status === 'error'
                    ? 'bg-[#e05a5a]'
                    : 'bg-[#6fba2c]'
                }`}
              />
              <div className="min-w-0">
                <div className="truncate font-black text-[#725d42]">{item.label}</div>
                {item.detail && (
                  <div className="mt-0.5 break-words text-[11px] font-bold leading-snug text-[#8a7657]">
                    {item.detail}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      </details>
    </div>
  )
}

function AdjustmentReceiptMessage({ receipt }: { receipt: AdjustmentReceipt }) {
  return (
    <div className="max-w-[92%] rounded-[22px_26px_20px_24px/24px_20px_26px_22px] border-[2.5px] border-[#a89878]/75 bg-[#fffdf5] px-4 py-3 text-[#725d42] shadow-[0_4px_0_0_#d4c9b4]">
      <div className="flex items-start gap-3">
        <span
          className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-full border-2 border-[#82d5bb] bg-[#e6f9f6] text-sm font-black text-[#0f766e] shadow-[0_2px_0_0_#11a89b]"
          aria-hidden="true"
        >
          ✓
        </span>
        <div className="min-w-0">
          <div className="text-[13px] font-black leading-tight text-[#794f27]">{receipt.title}</div>
          <div className="mt-1 text-sm font-black leading-relaxed text-[#725d42]">{receipt.detail}</div>
          {(receipt.chips.length > 0 || receipt.secondary) && (
            <div className="mt-2 flex flex-wrap items-center gap-1.5">
              {receipt.chips.map((chip) => (
                <span
                  key={chip}
                  className="rounded-full border border-[#82d5bb] bg-[#e6f9f6] px-2.5 py-1 text-[11px] font-black text-[#0f766e]"
                >
                  {chip}
                </span>
              ))}
              {receipt.secondary && (
                <span className="text-[11px] font-bold leading-relaxed text-[#8a7b66]">{receipt.secondary}</span>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function CandidateControlIntentMessage({ intent }: { intent: CandidateControlIntent }) {
  return (
    <div className="inline-flex max-w-full flex-col items-start gap-1 rounded-[22px] border-2 border-[#82d5bb] bg-[#e6f9f6] px-4 py-3 text-[#0f4c46] shadow-[0_3px_0_0_#11a89b]">
      <span className="text-sm font-black leading-snug">{intent.label}</span>
      <span className="text-[11px] font-bold leading-relaxed text-[#3f6f65]">{intent.detail}</span>
      {intent.chip && (
        <span className="rounded-full border border-[#82d5bb] bg-[#fff9e8] px-2.5 py-0.5 text-[10px] font-black text-[#0f766e]">
          {intent.chip}
        </span>
      )}
    </div>
  )
}

export function PlanPalChatColumn({
  draft,
  isDisabled = false,
  messages,
  onDraftChange,
  onExecuteActionCardOption,
  onOpenMerchant,
  onSelectPlanVariant,
  onSend,
  onSendStructuredPrompt,
}: PlanPalChatColumnProps) {
  const [tweaks, setTweaks] = useState<Record<string, string>>({})
  const [requirementOpen, setRequirementOpen] = useState<Record<string, boolean>>({})
  const [requirementDrafts, setRequirementDrafts] = useState<Record<string, string>>({})
  const [selectedMovieGroups, setSelectedMovieGroups] = useState<Record<string, string>>({})
  const scrollRef = useRef<HTMLDivElement>(null)
  const messageRenderKey = messages
    .map((message) => {
      const lastActivity = message.activity?.[message.activity.length - 1]
      return `${message.id}:${message.content.length}:${message.isLoading ? 'loading' : 'done'}:${message.activity?.length || 0}:${message.planVariants?.length || 0}:${lastActivity?.status || ''}`
    })
    .join('|')

  useEffect(() => {
    scrollRef.current?.scrollTo({
      top: scrollRef.current.scrollHeight,
      behavior: 'smooth',
    })
  }, [messageRenderKey])
  const [clarifyTime, setClarifyTime] = useState<Record<string, { start: number; end: number }>>({})
  const [clarifyCount, setClarifyCount] = useState<Record<string, number>>({})
  const [clarifyCustom, setClarifyCustom] = useState<Record<string, string>>({})
  const [clarifyChoices, setClarifyChoices] = useState<Record<string, Record<string, string>>>({})

  function formatHourLabel(hour: number) {
    return `${hour.toString().padStart(2, '0')}:00`
  }

  function clampHour(value: number) {
    return Math.max(0, Math.min(24, Math.round(value)))
  }

  function normalizeRange(range?: { start: number; end: number }) {
    if (!range) return { start: 14, end: 18 }
    let start = clampHour(range.start)
    let end = clampHour(range.end)
    if (start > end) {
      ;[start, end] = [end, start]
    }
    if (start === end) {
      if (end < 24) {
        end += 1
      } else {
        start -= 1
      }
    }
    return { start, end }
  }

  const timeRangeInputClass = [
    'absolute inset-0 m-0 h-11 w-full appearance-none bg-transparent pointer-events-none cursor-grab active:cursor-grabbing disabled:cursor-not-allowed',
    '[&::-webkit-slider-runnable-track]:h-11 [&::-webkit-slider-runnable-track]:bg-transparent',
    '[&::-webkit-slider-thumb]:pointer-events-auto [&::-webkit-slider-thumb]:h-7 [&::-webkit-slider-thumb]:w-7 [&::-webkit-slider-thumb]:appearance-none',
    '[&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:border-4 [&::-webkit-slider-thumb]:border-[#fff9e8] [&::-webkit-slider-thumb]:bg-[#19c8b9]',
    '[&::-webkit-slider-thumb]:shadow-[0_4px_0_0_#11a89b,0_0_0_2px_#725d42] [&::-webkit-slider-thumb]:transition-all [&::-webkit-slider-thumb]:duration-150',
    'hover:[&::-webkit-slider-thumb]:-translate-y-0.5 hover:[&::-webkit-slider-thumb]:bg-[#3dd4c6] hover:[&::-webkit-slider-thumb]:shadow-[0_5px_0_0_#11a89b,0_0_0_2px_#725d42]',
    'active:[&::-webkit-slider-thumb]:translate-y-0.5 active:[&::-webkit-slider-thumb]:shadow-[0_2px_0_0_#11a89b,0_0_0_2px_#725d42]',
    'focus-visible:[&::-webkit-slider-thumb]:outline-3 focus-visible:[&::-webkit-slider-thumb]:outline-offset-3 focus-visible:[&::-webkit-slider-thumb]:outline-[#ffcc00]',
    '[&::-moz-range-track]:h-11 [&::-moz-range-track]:bg-transparent',
    '[&::-moz-range-thumb]:pointer-events-auto [&::-moz-range-thumb]:h-5 [&::-moz-range-thumb]:w-5 [&::-moz-range-thumb]:rounded-full',
    '[&::-moz-range-thumb]:border-4 [&::-moz-range-thumb]:border-[#fff9e8] [&::-moz-range-thumb]:bg-[#19c8b9]',
    '[&::-moz-range-thumb]:shadow-[0_4px_0_0_#11a89b,0_0_0_2px_#725d42] [&::-moz-range-thumb]:transition-all [&::-moz-range-thumb]:duration-150',
    'hover:[&::-moz-range-thumb]:-translate-y-0.5 hover:[&::-moz-range-thumb]:bg-[#3dd4c6] hover:[&::-moz-range-thumb]:shadow-[0_5px_0_0_#11a89b,0_0_0_2px_#725d42]',
    'active:[&::-moz-range-thumb]:translate-y-0.5 active:[&::-moz-range-thumb]:shadow-[0_2px_0_0_#11a89b,0_0_0_2px_#725d42]',
    'focus-visible:[&::-moz-range-thumb]:outline-3 focus-visible:[&::-moz-range-thumb]:outline-offset-3 focus-visible:[&::-moz-range-thumb]:outline-[#ffcc00]',
  ].join(' ')

  function renderRangeSlider(messageId: string) {
    const current = normalizeRange(clarifyTime[messageId])
    const min = 0
    const max = 24
    const startPercent = (current.start / max) * 100
    const endPercent = (current.end / max) * 100
    const left = startPercent
    const right = 100 - endPercent
    const startThumbRaised = current.start >= current.end - 1

    return (
      <div className="grid gap-2.5 rounded-[24px] border-[2.5px] border-[#c4b89e] bg-[#f7f3df] px-3.5 pt-3 pb-2.5 shadow-[0_3px_0_0_#d4c9b4,inset_0_1px_0_rgba(255,255,255,0.7)]">
        <div
          className="inline-flex w-fit items-center justify-self-center gap-2 rounded-full border-2 border-[#82d5bb] bg-[#e6f9f6] px-3.5 py-1 text-[13px] font-black text-[#725d42] shadow-[0_3px_0_0_rgba(17,168,155,0.28)]"
          aria-hidden="true"
        >
          <span>{formatHourLabel(current.start)}</span>
          <span className="text-[11px] text-[#11a89b]">到</span>
          <span>{formatHourLabel(current.end)}</span>
        </div>
        <div className="relative h-[58px] px-0.5">
          <div className="absolute inset-x-0 top-[18px] h-3.5 rounded-full border-2 border-[#c4b89e] bg-[radial-gradient(circle,rgba(114,93,66,0.32)_0_2px,transparent_2.5px),linear-gradient(180deg,#f0e8d8_0%,#eadfca_100%)] bg-[length:25%_100%,100%_100%] bg-[position:0_50%,0_0] bg-repeat-x shadow-[inset_0_2px_3px_rgba(114,93,66,0.14)]" />
          <div
            className="absolute top-[18px] h-3.5 rounded-full border-2 border-[#11a89b] bg-gradient-to-b from-[#3dd4c6] to-[#19c8b9] shadow-[0_3px_0_0_#11a89b,inset_0_1px_0_rgba(255,255,255,0.5)]"
            style={{ left: `${left}%`, right: `${right}%` }}
          />
          <input
            type="range"
            aria-label="开始时间"
            aria-valuetext={`${formatHourLabel(current.start)} 开始`}
            min={min}
            max={max}
            step={1}
            disabled={isDisabled}
            value={current.start}
            className={timeRangeInputClass}
            style={{ zIndex: startThumbRaised ? 4 : 3 }}
            onChange={(e) => {
              const nextStart = Math.min(clampHour(Number(e.target.value)), current.end - 1)
              setClarifyTime((prev) => ({ ...prev, [messageId]: { start: nextStart, end: current.end } }))
            }}
          />
          <input
            type="range"
            aria-label="结束时间"
            aria-valuetext={`${formatHourLabel(current.end)} 结束`}
            min={min}
            max={max}
            step={1}
            disabled={isDisabled}
            value={current.end}
            className={timeRangeInputClass}
            style={{ zIndex: startThumbRaised ? 3 : 4 }}
            onChange={(e) => {
              const nextEnd = Math.max(clampHour(Number(e.target.value)), current.start + 1)
              setClarifyTime((prev) => ({ ...prev, [messageId]: { start: current.start, end: nextEnd } }))
            }}
          />
          <div className="absolute inset-x-0 bottom-0 flex justify-between text-[10px] font-black text-[#8a7b66]">
            <span>0</span>
            <span>6</span>
            <span>12</span>
            <span>18</span>
            <span>24</span>
          </div>
        </div>
      </div>
    )
  }

  function submitInlinePrompt(messageId: string, source = 'chat-card') {
    const custom = tweaks[messageId] || ''
    if (!custom.trim()) return
    onSendStructuredPrompt?.(custom, { source })
    setTweaks((prev) => ({ ...prev, [messageId]: '' }))
  }

  function recommendationControlPrompt(
    marker: 'REFRESH_CANDIDATES' | 'REFINE_CANDIDATES',
    card: NonNullable<ChatMessage['actionCard']>,
    requirement = '',
  ) {
    const isMovieCard = card.cardKind === 'MOVIE_SCREENING'
    const movieIds = card.options
      .map((option) => option.screening?.movieId)
      .filter((value): value is string => Boolean(value))
    const optionIds = Array.from(new Set([...card.options.map((option) => option.id).filter(Boolean), ...movieIds]))
    const poiIds = isMovieCard ? [] : Array.from(new Set(card.options.flatMap((option) => option.poiIds || [])))
    const screeningIds = card.options
      .map((option) => option.screening?.screeningId)
      .filter((value): value is string => Boolean(value))
    const parts = [
      `[${marker}]`,
      `cardKind=${card.cardKind || 'POI'}`,
      optionIds.length ? `excludeOptionIds=${optionIds.join(',')}` : '',
      poiIds.length ? `excludePoiIds=${poiIds.join(',')}` : '',
      screeningIds.length ? `excludeScreeningIds=${screeningIds.join(',')}` : '',
      requirement.trim(),
    ].filter(Boolean)
    return parts.join(' ')
  }

  function refreshRecommendations(messageId: string, card: NonNullable<ChatMessage['actionCard']>) {
    onSendStructuredPrompt?.(recommendationControlPrompt('REFRESH_CANDIDATES', card), {
      source: 'action-card:REFRESH_CANDIDATES',
      userMessage: `换一批${candidateKindLabel(card.cardKind)}`,
    })
    setRequirementOpen((prev) => ({ ...prev, [messageId]: false }))
  }

  function submitRecommendationRequirement(messageId: string, card: NonNullable<ChatMessage['actionCard']>) {
    const text = requirementDrafts[messageId] || ''
    if (!text.trim()) return
    onSendStructuredPrompt?.(recommendationControlPrompt('REFINE_CANDIDATES', card, text), {
      source: 'action-card:REFINE_CANDIDATES',
      userMessage: `按要求重新筛选${candidateKindLabel(card.cardKind)}：${text.trim()}`,
    })
    setRequirementDrafts((prev) => ({ ...prev, [messageId]: '' }))
    setRequirementOpen((prev) => ({ ...prev, [messageId]: false }))
  }

  function renderSlotCollectionCard(message: ChatMessage) {
    const card = message.actionCard
    if (!card || card.cardKind !== 'SLOT_COLLECTION') return null

    const timeOptions = card.options.filter((option) => option.optionKind === 'SLOT_TIME_RANGE')
    const headcountOptions = card.options.filter((option) => option.optionKind === 'SLOT_HEADCOUNT')
    const groupedOptions = card.options
      .filter((option) => option.optionKind && !['SLOT_TIME_RANGE', 'SLOT_HEADCOUNT'].includes(option.optionKind))
      .reduce<Record<string, ActionOption[]>>((groups, option) => {
        const key = option.optionKind || 'SLOT_OTHER'
        groups[key] = [...(groups[key] || []), option]
        return groups
      }, {})
    const selectedTime = timeOptions.length > 0 ? normalizeRange(clarifyTime[message.id]) : null
    const selectedCount = clarifyCount[message.id] || 0
    const selectedChoices = clarifyChoices[message.id] || {}
    const custom = clarifyCustom[message.id] || ''
    const selectedHeadcount = headcountOptions.find((option) => {
      const value = Number((option.prompt || option.label).match(/\d+/)?.[0] || 0)
      return value === selectedCount
    })
    const requiredGroups = Object.keys(groupedOptions)
    const canSubmit =
      (timeOptions.length === 0 || Boolean(selectedTime)) &&
      (headcountOptions.length === 0 || Boolean(selectedCount)) &&
      requiredGroups.every((group) => Boolean(selectedChoices[group])) &&
      (Boolean(selectedTime) || Boolean(selectedCount) || requiredGroups.length > 0 || Boolean(custom.trim()))

    const groupTitle: Record<string, string> = {
      SLOT_LOCATION_SCOPE: '地点范围',
      SLOT_PACE: '活动节奏',
      SLOT_ORDER_PREFERENCE: '先后顺序',
      SLOT_BUDGET_LEVEL: '预算偏好',
    }

    const optionClass = (isSelected: boolean) =>
      [
        'min-h-10 px-3 py-2.5 text-xs font-black rounded-[16px] border-2 cursor-pointer transition-all duration-150 disabled:cursor-not-allowed disabled:opacity-50',
        isSelected
          ? 'border-[#11a89b]! bg-[#e6f9f6]! text-[#0f4c46]! shadow-[0_3px_0_0_#11a89b]!'
          : 'border-[#c4b89e]! bg-[#fff9e8]! text-[#725d42]! shadow-[0_3px_0_0_#d4c9b4]! hover:-translate-y-0.5 hover:border-[#a89878]! hover:bg-[#ffeea0]! active:translate-y-[1px] active:shadow-[0_1px_0_0_#d4c9b4]!',
      ].join(' ')

    const sectionTitle = (label: string) => (
      <div className="flex items-center gap-2 text-xs font-black text-[#794f27]">
        <span className="h-2.5 w-2.5 rounded-full border-2 border-[#11a89b] bg-[#e6f9f6] shadow-[0_1px_0_0_#11a89b]" />
        <span>{label}</span>
      </div>
    )

    return (
      <div className="mt-4 overflow-hidden rounded-[22px_26px_20px_24px/24px_20px_26px_22px] border-[2.5px] border-[#a89878]/75 bg-[#fffdf5] text-[#725d42] shadow-[0_4px_0_0_#d4c9b4]">
        <div className="border-b-2 border-[#e5dac0] bg-[linear-gradient(135deg,#fff9e8_0%,#fff3c4_58%,#e6f9f6_100%)] px-4 py-3">
          <span className="inline-flex w-fit rounded-full border-2 border-[#82d5bb] bg-[#e6f9f6] px-3 py-1 text-[11px] font-black uppercase tracking-wide text-[#0f766e] shadow-[0_2px_0_0_rgba(17,168,155,0.28)]">
            补充信息
          </span>
          <h4 className="m-0 mt-2 text-[15px] font-black leading-snug text-[#794f27]">{card.title}</h4>
          <p className="m-0 mt-1 text-xs font-semibold leading-relaxed text-[#725d42]">{card.description}</p>
        </div>

        <div className="flex flex-col gap-3.5 px-3.5 py-4">

        {timeOptions.length > 0 && (
          <div className="flex flex-col gap-2">
            {sectionTitle('出行时间段')}
            {renderRangeSlider(message.id)}
          </div>
        )}

        {headcountOptions.length > 0 && (
          <div className="flex flex-col gap-2">
            {sectionTitle('出行人数')}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
              {headcountOptions.map((option) => {
                const value = Number((option.prompt || option.label).match(/\d+/)?.[0] || 0)
                const isSelected = selectedCount === value
                return (
                  <button
                    key={option.id}
                    type="button"
                    disabled={isDisabled}
                    className={optionClass(isSelected)}
                    onClick={() => setClarifyCount((prev) => ({ ...prev, [message.id]: value }))}
                  >
                    {option.label}
                  </button>
                )
              })}
            </div>
          </div>
        )}

        {Object.entries(groupedOptions).map(([group, options]) => (
          <div key={group} className="flex flex-col gap-2">
            {sectionTitle(groupTitle[group] || '补充选项')}
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
              {options.map((option) => {
                const value = option.prompt || option.label
                const isSelected = selectedChoices[group] === value
                return (
                  <button
                    key={option.id}
                    type="button"
                    disabled={isDisabled}
                    className={optionClass(isSelected)}
                    onClick={() =>
                      setClarifyChoices((prev) => ({
                        ...prev,
                        [message.id]: {
                          ...(prev[message.id] || {}),
                          [group]: value,
                        },
                      }))
                    }
                  >
                    {option.label}
                  </button>
                )
              })}
            </div>
          </div>
        ))}

        {card.allowCustomInput && (
          <div className="flex flex-col gap-2">
            {sectionTitle('其它偏好')}
            <input
              type="text"
              disabled={isDisabled}
              className="w-full rounded-[18px] border-[2.5px] border-[#c4b89e] bg-[#f7f3df] px-3.5 py-2.5 text-xs font-bold text-[#725d42] shadow-[0_3px_0_0_#d4c9b4] outline-none transition-all placeholder-[#9f927d]/80 focus:border-[#ffcc00] focus:shadow-[0_3px_0_0_#e0b800,0_0_0_3px_rgba(255,204,0,0.15)] disabled:opacity-50"
              placeholder={card.inputPlaceholder || '例如：室内一点，别太远'}
              value={custom}
              onChange={(e) => setClarifyCustom((prev) => ({ ...prev, [message.id]: e.target.value }))}
            />
          </div>
        )}

        <Button
          type="primary"
          disabled={isDisabled || !canSubmit}
          className="mt-0.5 w-full bg-[#ffcc00]! border-[#ffcc00]! text-[#725d42]! shadow-[0_4px_0_0_#dba90e]! hover:-translate-y-0.5 active:translate-y-[1px] active:shadow-[0_1px_0_0_#dba90e]! transition-all duration-150 font-black text-sm py-2.5! h-auto! rounded-[18px]!"
          onClick={() => {
            if (!canSubmit) return
            const parts = [
              selectedTime
                ? `我计划在 ${formatHourLabel(selectedTime.start)} 到 ${formatHourLabel(selectedTime.end)} 出行`
                : '',
              selectedCount > 0 ? `总共 ${selectedCount} 个人` : selectedHeadcount?.prompt || '',
              ...Object.values(selectedChoices),
              custom.trim(),
            ].filter(Boolean)
            onDraftChange('')
            onSend(parts.join('，'))
          }}
        >
          继续让 PlanPal 安排
        </Button>
        </div>
      </div>
    )
  }

  function renderRecommendationControls(message: ChatMessage, card: NonNullable<ChatMessage['actionCard']>) {
    const requirement = requirementDrafts[message.id] || ''
    const isOpen = Boolean(requirementOpen[message.id])

    return (
      <div className="mt-3 grid gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            disabled={isDisabled}
            className="inline-flex min-h-[30px] items-center gap-1.5 rounded-full border-2 border-[#82d5bb] bg-[#e6f9f6] px-3 text-[11px] font-black text-[#0f766e] shadow-[0_3px_0_0_#11a89b] transition-all hover:-translate-y-0.5 hover:bg-[#d7f6ef] active:translate-y-[1px] active:shadow-[0_1px_0_0_#11a89b] disabled:cursor-not-allowed disabled:opacity-50"
            onClick={() => refreshRecommendations(message.id, card)}
          >
            <span aria-hidden="true">↻</span>
            换一批
          </button>
          <button
            type="button"
            disabled={isDisabled}
            className="inline-flex min-h-[30px] items-center gap-1.5 rounded-full border-2 border-[#f7cd67] bg-[#fff3c4] px-3 text-[11px] font-black text-[#794f27] shadow-[0_3px_0_0_#dba90e] transition-all hover:-translate-y-0.5 hover:bg-[#ffeea0] active:translate-y-[1px] active:shadow-[0_1px_0_0_#dba90e] disabled:cursor-not-allowed disabled:opacity-50"
            onClick={() => setRequirementOpen((prev) => ({ ...prev, [message.id]: !prev[message.id] }))}
          >
            <span aria-hidden="true">✎</span>
            描述要求
          </button>
        </div>
        {isOpen && (
          <div className="grid grid-cols-[1fr_auto] gap-2 rounded-[18px] border-2 border-[#c4b89e] bg-[#f7f3df] p-2 shadow-[0_2px_0_0_#d4c9b4]">
            <input
              type="text"
              disabled={isDisabled}
              className="min-w-0 rounded-[14px] border-[2.5px] border-[#c4b89e] bg-[#fff9e8] px-3 py-2 text-xs font-bold text-[#725d42] shadow-[0_2px_0_0_#d4c9b4] outline-none placeholder-[#9f927d]/80 focus:border-[#ffcc00] focus:shadow-[0_2px_0_0_#e0b800,0_0_0_3px_rgba(255,204,0,0.15)] disabled:opacity-50"
              placeholder="比如：低糖、少排队、别太远、想安静一点"
              value={requirement}
              onChange={(event) => setRequirementDrafts((prev) => ({ ...prev, [message.id]: event.target.value }))}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && requirement.trim()) {
                  submitRecommendationRequirement(message.id, card)
                }
              }}
            />
            <button
              type="button"
              disabled={isDisabled || !requirement.trim()}
              className="rounded-[14px] border-2 border-[#ffcc00] bg-[#ffcc00] px-3 text-xs font-black text-[#725d42] shadow-[0_2px_0_0_#dba90e] transition-all hover:-translate-y-0.5 active:translate-y-[1px] active:shadow-none disabled:pointer-events-none disabled:opacity-50"
              onClick={() => submitRecommendationRequirement(message.id, card)}
            >
              重筛
            </button>
          </div>
        )}
      </div>
    )
  }

  function renderDecisionChips(option: ActionOption) {
    const chips = [...(option.decisionReasons || []), ...(option.tradeoffs || [])].slice(0, 4)
    if (!chips.length && option.score == null) return null
    return (
      <div className="mt-2 flex flex-wrap items-center gap-1.5">
        {option.score != null && (
          <span className="rounded-full border border-[#82d5bb] bg-[#e6f9f6] px-2 py-0.5 text-[10px] font-black text-[#0f766e]">
            {Math.round(option.score)} 分
          </span>
        )}
        {chips.map((chip) => (
          <span key={chip} className="rounded-full border border-[#e5dac0] bg-[#f3ead3] px-2 py-0.5 text-[10px] font-black text-[#725d42]">
            {chip}
          </span>
        ))}
      </div>
    )
  }

  function renderMovieActionCard(message: ChatMessage) {
    const card = message.actionCard
    if (!card || card.cardKind !== 'MOVIE_SCREENING') return null

    const movieGroups = card.options.reduce<Record<string, ActionOption[]>>((groups, option) => {
      const key = option.screening?.movieId || option.label
      groups[key] = [...(groups[key] || []), option]
      return groups
    }, {})
    const selectedMovieId = selectedMovieGroups[message.id]

    return (
      <div className="mt-4 overflow-hidden rounded-[22px_26px_20px_24px/24px_20px_26px_22px] border-[2.5px] border-[#a89878]/75 bg-[#fffdf5] text-[#725d42] shadow-[0_4px_0_0_#d4c9b4]">
        <div className="border-b-2 border-[#e5dac0] bg-[linear-gradient(135deg,#fff9e8_0%,#fff3c4_58%,#e6f9f6_100%)] px-4 py-3">
          <span className="inline-flex w-fit rounded-full border-2 border-[#82d5bb] bg-[#e6f9f6] px-3 py-1 text-[11px] font-black uppercase tracking-wide text-[#0f766e] shadow-[0_2px_0_0_rgba(17,168,155,0.28)]">
            电影场次
          </span>
          <h4 className="m-0 mt-2 text-[15px] font-black leading-snug text-[#794f27]">{card.title}</h4>
          {card.description && (
            <p className="m-0 mt-1 text-xs font-semibold text-[#725d42] leading-relaxed">{card.description}</p>
          )}
          {renderRecommendationControls(message, card)}
        </div>

        <div className="flex flex-col gap-3 px-3.5 py-4">
          {Object.entries(movieGroups).map(([movieId, options]) => {
            const first = options[0]
            const cinemaCount = new Set(options.map((option) => option.screening?.cinemaId || option.poiPreview?.poiId)).size
            const formats = Array.from(new Set(options.map((option) => option.screening?.format).filter(Boolean))).slice(0, 3)
            const isSelected = selectedMovieId === movieId
            if (!isSelected) {
              return (
                <button
                  key={movieId}
                  type="button"
                  disabled={isDisabled}
                  className="w-full rounded-[18px] border-2 border-[#c4b89e] bg-[#fff9e8] px-3 py-3 text-left shadow-[0_3px_0_0_#d4c9b4] transition-all hover:-translate-y-0.5 hover:border-[#82d5bb] hover:bg-[#fdfdf5] disabled:cursor-not-allowed disabled:opacity-60"
                  onClick={() => setSelectedMovieGroups((prev) => ({ ...prev, [message.id]: movieId }))}
                >
                  <div className="flex items-start gap-3">
                    <img
                      src={merchantPlaceholder}
                      alt=""
                      className="h-12 w-12 rounded-[16px] border-2 border-[#e5dac0] bg-[#f7f3df] object-cover shadow-[0_2px_0_0_#d4c9b4]"
                    />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <h5 className="m-0 text-sm font-black leading-snug text-[#794f27]">
                          {first.screening?.movieTitle || first.label}
                        </h5>
                        {formats.map((format) => (
                          <span key={format} className="rounded-full border border-[#82d5bb] bg-[#e6f9f6] px-2 py-0.5 text-[10px] font-black text-[#0f766e]">
                            {format}
                          </span>
                        ))}
                      </div>
                      <p className="m-0 mt-1 text-[11px] font-bold leading-relaxed text-[#725d42]">
                        {cinemaCount} 家影院 / {options.length} 个场次
                      </p>
                      {renderDecisionChips(first)}
                    </div>
                    <span className="mt-1 rounded-full border-2 border-[#f7cd67] bg-[#fff3c4] px-2.5 py-1 text-[11px] font-black text-[#794f27] shadow-[0_2px_0_0_#dba90e]">
                      选电影
                    </span>
                  </div>
                </button>
              )
            }
            const screeningsByCinema = options.reduce<Record<string, ActionOption[]>>((groups, option) => {
              const key = option.screening?.cinemaId || option.poiPreview?.poiId || 'cinema'
              groups[key] = [...(groups[key] || []), option]
              return groups
            }, {})
            return (
              <section key={movieId} className="rounded-[18px] border-2 border-[#c4b89e] bg-[#fff9e8] p-3 shadow-[0_3px_0_0_#d4c9b4]">
                <div className="flex items-start gap-3">
                  <img
                    src={merchantPlaceholder}
                    alt=""
                    className="h-12 w-12 rounded-[16px] border-2 border-[#e5dac0] bg-[#f7f3df] object-cover shadow-[0_2px_0_0_#d4c9b4]"
                  />
                  <div className="min-w-0">
                    <h5 className="m-0 text-sm font-black leading-snug text-[#794f27]">{first.screening?.movieTitle || first.label}</h5>
                    <p className="m-0 mt-1 text-[11px] font-semibold leading-relaxed text-[#725d42]">
                      已选择电影，下面选择影院和具体开场时间
                    </p>
                    {renderDecisionChips(first)}
                  </div>
                </div>
                <div className="mt-3 grid gap-2">
                  {Object.entries(screeningsByCinema).map(([cinemaId, cinemaOptions]) => (
                    <div key={cinemaId} className="rounded-[16px] border-2 border-[#e5dac0] bg-[#fdfdf5] px-3 py-2">
                      <div className="text-[12px] font-black text-[#794f27]">
                        {cinemaOptions[0].screening?.cinemaName || cinemaOptions[0].poiPreview?.name}
                      </div>
                      <div className="mt-2 flex flex-wrap gap-2">
                        {cinemaOptions.map((option) => (
                          <button
                            key={option.id}
                            type="button"
                            disabled={isDisabled}
                            className="rounded-full border-2 border-[#82d5bb] bg-[#e6f9f6] px-3 py-1.5 text-left text-[11px] font-black text-[#0f4c46] shadow-[0_3px_0_0_#11a89b] transition-all hover:-translate-y-0.5 hover:bg-[#d7f6ef] active:translate-y-[1px] active:shadow-[0_1px_0_0_#11a89b] disabled:cursor-not-allowed disabled:opacity-60"
                            onClick={() => onExecuteActionCardOption?.(message.id, option)}
                            title={`${option.screening?.hall || ''} ${option.screening?.format || ''}`}
                          >
                            {option.screening ? `${option.screening.startTime} · ${option.screening.format} · ¥${option.screening.pricePerTicket}` : option.label}
                          </button>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </section>
            )
          })}
        </div>
      </div>
    )
  }

  function renderGenericActionCard(message: ChatMessage) {
    const card = message.actionCard
    if (!card || card.cardKind === 'SLOT_COLLECTION' || card.cardKind === 'PLAN_CHOICE') return null
    if (card.cardKind === 'MOVIE_SCREENING') return renderMovieActionCard(message)

    const custom = tweaks[message.id] || ''
    const canSubmitCustom = card.allowCustomInput && custom.trim().length > 0

    return (
      <div className="mt-4 overflow-hidden rounded-[22px_26px_20px_24px/24px_20px_26px_22px] border-[2.5px] border-[#a89878]/75 bg-[#fffdf5] text-[#725d42] shadow-[0_4px_0_0_#d4c9b4]">
        <div className="border-b-2 border-[#e5dac0] bg-[linear-gradient(135deg,#fff9e8_0%,#fff3c4_58%,#e6f9f6_100%)] px-4 py-3">
          <span className="inline-flex w-fit rounded-full border-2 border-[#82d5bb] bg-[#e6f9f6] px-3 py-1 text-[11px] font-black uppercase tracking-wide text-[#0f766e] shadow-[0_2px_0_0_rgba(17,168,155,0.28)]">
            推荐选项
          </span>
          <h4 className="m-0 mt-2 text-[15px] font-black leading-snug text-[#794f27]">{card.title}</h4>
          {card.description && (
            <p className="m-0 mt-1 text-xs font-semibold text-[#725d42] leading-relaxed">{card.description}</p>
          )}
          {renderRecommendationControls(message, card)}
        </div>

        <div className="flex flex-col gap-3.5 px-3.5 py-4">
        <div className="flex flex-col gap-2.5">
          {card.options.map((option) => {
            const preview = option.poiPreview
            const movie = isMovieScreeningOption(option, card.cardKind)
            return (
              <button
                key={option.id}
                type="button"
                disabled={isDisabled}
                className="w-full cursor-pointer rounded-[18px] border-2 border-[#c4b89e] bg-[#fff9e8] px-3 py-3 text-left shadow-[0_3px_0_0_#d4c9b4] transition-all hover:-translate-y-0.5 hover:border-[#a89878] hover:bg-[#ffeea0] hover:shadow-[0_4px_0_0_#d4c9b4] active:translate-y-[1px] active:shadow-[0_1px_0_0_#d4c9b4] disabled:cursor-not-allowed disabled:opacity-60"
                onClick={() => onExecuteActionCardOption?.(message.id, option)}
              >
                <div className="grid grid-cols-[48px_minmax(0,1fr)] gap-3">
                  <img
                    src={merchantPlaceholder}
                    alt=""
                    className="h-12 w-12 rounded-[16px] object-cover border-2 border-[#e5dac0] bg-[#f7f3df] shadow-[0_2px_0_0_#d4c9b4]"
                  />
                  <div className="min-w-0">
                    <strong className="block truncate text-sm font-black text-[#794f27]">{option.label}</strong>
                    {option.description && (
                      <span className="mt-1 block text-[11px] font-semibold leading-relaxed text-[#725d42]">
                        {option.description}
                      </span>
                    )}
                    {preview && (
                      <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[10px] font-black text-[#725d42]/80">
                        <span className="rounded-full border border-[#82d5bb] bg-[#e6f9f6] px-2 py-0.5 text-[#0f766e]">
                          {movie ? '电影' : preview.category}
                        </span>
                        <span className="rounded-full border border-[#f7cd67]/70 bg-[#fff3c4] px-2 py-0.5">
                          {preview.distanceKm.toFixed(1)} km
                        </span>
                        {preview.tags.slice(0, 3).map((tag) => (
                          <span key={tag} className="rounded-full border border-[#e5dac0] bg-[#f3ead3] px-2 py-0.5">
                            {tag}
                          </span>
                        ))}
                      </div>
                    )}
                    {renderDecisionChips(option)}
                  </div>
                </div>
              </button>
            )
          })}
        </div>

        {card.allowCustomInput && (
          <div className="grid grid-cols-[1fr_auto] gap-2">
            <input
              type="text"
              disabled={isDisabled}
              className="min-w-0 rounded-[18px] border-[2.5px] border-[#c4b89e] bg-[#f7f3df] px-3.5 py-2.5 text-xs font-bold text-[#725d42] shadow-[0_3px_0_0_#d4c9b4] outline-none transition-all placeholder-[#9f927d]/80 focus:border-[#ffcc00] focus:shadow-[0_3px_0_0_#e0b800,0_0_0_3px_rgba(255,204,0,0.15)] disabled:opacity-50"
              placeholder={card.inputPlaceholder || 'Tell PlanPal what to refine'}
              value={custom}
              onChange={(event) => setTweaks((prev) => ({ ...prev, [message.id]: event.target.value }))}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && canSubmitCustom) {
                  submitInlinePrompt(message.id)
                }
              }}
            />
            <button
              type="button"
              disabled={isDisabled || !canSubmitCustom}
              className="rounded-[18px] border-2 border-[#ffcc00] bg-[#ffcc00] px-4 py-2.5 text-xs font-black text-[#725d42] shadow-[0_3px_0_0_#dba90e] transition-all hover:-translate-y-0.5 active:translate-y-[1px] active:shadow-[0_1px_0_0_#dba90e] disabled:pointer-events-none disabled:opacity-50"
              onClick={() => submitInlinePrompt(message.id)}
            >
              发送
            </button>
          </div>
        )}
        </div>
      </div>
    )
  }

  function renderPlanVariants(message: ChatMessage) {
    return null

    const variants = (message.planVariants || []).filter((variant) => variant.planId)
    if (!variants.length) return null

    return (
      <div className="mt-4 overflow-hidden rounded-[22px_26px_20px_24px/24px_20px_26px_22px] border-[2.5px] border-[#a89878]/75 bg-[#fffdf5] text-[#725d42] shadow-[0_4px_0_0_#d4c9b4]">
        <div className="border-b-2 border-[#e5dac0] bg-[linear-gradient(135deg,#fff9e8_0%,#fff3c4_58%,#e6f9f6_100%)] px-4 py-3">
          <span className="inline-flex w-fit rounded-full border-2 border-[#82d5bb] bg-[#e6f9f6] px-3 py-1 text-[11px] font-black uppercase tracking-wide text-[#0f766e] shadow-[0_2px_0_0_rgba(17,168,155,0.28)]">
            3 条完整可执行行程
          </span>
          <h4 className="m-0 mt-2 text-[15px] font-black leading-snug text-[#794f27]">
            可以直接切换查看，也可以继续输入偏好让 PlanPal 重搜
          </h4>
        </div>

        <div className="flex flex-col gap-2.5 px-3.5 py-4">
          {variants.map((variant, index) => (
            <div
              key={variant.planId}
              className="rounded-[18px] border-2 border-[#c4b89e] bg-[#fff9e8] px-3 py-3 shadow-[0_3px_0_0_#d4c9b4]"
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="text-sm font-black text-[#794f27]">
                    方案 {index + 1}
                    {variant.stepCount > 0 && (
                      <span className="ml-2 rounded-full bg-[#e6f9f6] px-2 py-0.5 text-[10px] text-[#0f766e]">
                        {variant.stepCount} 步
                      </span>
                    )}
                  </div>
                  <p className="m-0 mt-1 text-xs font-semibold leading-relaxed text-[#725d42]">
                    {variant.notificationText || variant.summary}
                  </p>
                </div>
                <button
                  type="button"
                  disabled={isDisabled}
                  className="shrink-0 rounded-[16px] border-2 border-[#82d5bb] bg-[#e6f9f6] px-3 py-2 text-xs font-black text-[#0f4c46] shadow-[0_3px_0_0_#11a89b] transition-all hover:-translate-y-0.5 hover:bg-[#d7f6ef] active:translate-y-[1px] active:shadow-[0_1px_0_0_#11a89b] disabled:pointer-events-none disabled:opacity-50"
                  onClick={() => onSelectPlanVariant?.(variant.planId)}
                >
                  查看这条
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>
    )
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

        {messages.map((message) => {
          const isPlanPal = message.role === 'planpal'
          const activity = isPlanPal ? message.activity : null
          const adjustmentReceipt = isPlanPal && !message.isLoading ? parseAdjustmentReceipt(message.content) : null
          const candidateControlIntent =
            message.role === 'user' && !message.isLoading ? parseCandidateControlIntent(message.content) : null

          if (activity && activity.length > 0) {
            return (
              <div key={message.id} className="flex flex-col items-start">
                <CompactActivityMessage activity={activity} />
              </div>
            )
          }

          return (
            <div
              key={message.id}
              className={`flex flex-col ${message.role === 'user' ? 'items-end' : 'items-start'}`}
            >
              <div
                className={
                  adjustmentReceipt || candidateControlIntent
                    ? 'max-w-[95%] text-sm font-bold leading-relaxed'
                    : `max-w-[95%] rounded-[22px] border-2 px-4 py-3 text-sm font-bold leading-relaxed shadow-[0_3px_0_0_#d4c9b4] ${
                        message.role === 'user'
                          ? 'border-[#82d5bb] bg-[#e6f9f6] text-[#0f4c46]'
                          : 'border-[#c4b89e] bg-[#fff9e8] text-[#725d42]'
                      }`
                }
              >
                {message.isLoading ? (
                  <div className="flex min-w-[150px] flex-col gap-2">
                    {message.content ? (
                      <div className="text-sm font-bold leading-relaxed text-[#725d42]">
                        {isPlanPal ? parseAndRenderContent(message.content) : message.content}
                      </div>
                    ) : null}
                    <div
                      role="status"
                      aria-label="PlanPal 正在生成回复"
                      className="inline-flex w-fit items-center gap-2 rounded-full border border-[#82d5bb]/70 bg-[#e6f9f6] px-3 py-1.5 text-[11px] font-black text-[#0f4c46]"
                    >
                      <ThinkingDots />
                      <span>{message.content ? '继续生成中' : 'PlanPal 正在处理'}</span>
                    </div>
                  </div>
                ) : adjustmentReceipt ? (
                  <AdjustmentReceiptMessage receipt={adjustmentReceipt} />
                ) : candidateControlIntent ? (
                  <CandidateControlIntentMessage intent={candidateControlIntent} />
                ) : isPlanPal ? (
                  <StreamingContent
                    content={message.content}
                    active={Boolean(message.isStreaming)}
                    renderContent={parseAndRenderContent}
                  />
                ) : (
                  message.content
                )}

                {renderSlotCollectionCard(message)}
                {renderGenericActionCard(message)}
                {renderPlanVariants(message)}

                {isPlanPal && message.actionCard && message.actionCard.cardKind === 'PLAN_CHOICE' && (
                  <div className="mt-4 overflow-hidden rounded-[22px_26px_20px_24px/24px_20px_26px_22px] border-[2.5px] border-[#a89878]/75 bg-[#fffdf5] text-[#725d42] shadow-[0_4px_0_0_#d4c9b4]">
                    <div className="border-b-2 border-[#e5dac0] bg-[linear-gradient(135deg,#fff9e8_0%,#fff3c4_58%,#e6f9f6_100%)] px-4 py-3">
                      <span className="inline-flex w-fit rounded-full border-2 border-[#82d5bb] bg-[#e6f9f6] px-3 py-1 text-[11px] font-black uppercase tracking-wide text-[#0f766e] shadow-[0_2px_0_0_rgba(17,168,155,0.28)]">
                        推荐操作
                      </span>
                      <h4 className="m-0 mt-2 text-[15px] font-black leading-snug text-[#794f27]">
                        {message.actionCard.title}
                      </h4>
                      <p className="m-0 mt-1 text-xs font-semibold text-[#725d42] leading-relaxed">
                        {message.actionCard.description}
                      </p>
                    </div>

                    <div className="flex flex-col gap-2.5 px-3.5 py-4">
                      {message.actionCard.options.map((option) => (
                        <button
                          key={option.id}
                          type="button"
                          disabled={isDisabled}
                          className="w-full cursor-pointer select-none rounded-[18px] border-2 border-[#82d5bb] bg-[#e6f9f6] px-4 py-3 text-left text-[#0f4c46] shadow-[0_4px_0_0_#11a89b] transition-all hover:-translate-y-0.5 hover:border-[#11a89b] hover:bg-[#d7f6ef] hover:shadow-[0_5px_0_0_#11a89b] active:translate-y-[1px] active:shadow-[0_2px_0_0_#11a89b] disabled:cursor-not-allowed disabled:opacity-60"
                          onClick={() => onExecuteActionCardOption?.(message.id, option)}
                        >
                          <strong className="text-sm font-black leading-tight text-[#0f4c46]">{option.label}</strong>
                          {option.description && (
                            <span className="mt-1 block text-[11px] font-semibold leading-relaxed text-[#3f6f65]">
                              {option.description}
                            </span>
                          )}
                        </button>
                      ))}
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
