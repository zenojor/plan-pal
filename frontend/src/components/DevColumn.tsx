import { useEffect, useMemo, useRef, useState } from 'react'
import type { AgentPlanIntent, AgentPlanResponse, AgentPlanStreamEvent } from '../api/agent'
import type { PlanNode } from '../types/plan'

type DevColumnProps = {
  plan: AgentPlanResponse | null
  nodes: PlanNode[]
  events: AgentPlanStreamEvent[]
  onClearEvents?: () => void
}

type EventFilter = 'all' | 'core' | 'problems'

type EventTone = {
  label: string
  dot: string
  badge: string
  group: 'system' | 'intent' | 'thinking' | 'tool' | 'result' | 'terminal'
}

const EVENT_TONES: Record<string, EventTone> = {
  START: { label: '启动', dot: 'bg-[#82d5bb]', badge: 'bg-[#e6f9f6] text-[#0f766e]', group: 'system' },
  PLAN_STARTED: { label: '规划启动', dot: 'bg-[#82d5bb]', badge: 'bg-[#e6f9f6] text-[#0f766e]', group: 'system' },
  INTENT: { label: '意图', dot: 'bg-[#f7cd67]', badge: 'bg-[#fff4c2] text-[#7a5a00]', group: 'intent' },
  INTENT_EXTRACTED: { label: '意图完成', dot: 'bg-[#f7cd67]', badge: 'bg-[#fff4c2] text-[#7a5a00]', group: 'intent' },
  THOUGHT: { label: '思考', dot: 'bg-[#8ab8e0]', badge: 'bg-[#e9f4ff] text-[#23547a]', group: 'thinking' },
  PLAN_NARRATIVE: { label: '叙事', dot: 'bg-[#8ab8e0]', badge: 'bg-[#e9f4ff] text-[#23547a]', group: 'thinking' },
  ACTION: { label: '动作', dot: 'bg-[#e59266]', badge: 'bg-[#ffe6d8] text-[#a84612]', group: 'tool' },
  CANDIDATES_SEARCHING: { label: '搜索', dot: 'bg-[#e59266]', badge: 'bg-[#ffe6d8] text-[#a84612]', group: 'tool' },
  AVAILABILITY_CHECKED: { label: '可用性', dot: 'bg-[#b77dee]', badge: 'bg-[#f2e7ff] text-[#663399]', group: 'tool' },
  OBSERVATION: { label: '观察', dot: 'bg-[#c4b89e]', badge: 'bg-[#efebe0] text-[#5f5344]', group: 'result' },
  WEATHER_CHECKED: { label: '天气', dot: 'bg-[#889df0]', badge: 'bg-[#edf0ff] text-[#4054a8]', group: 'result' },
  CANDIDATES_FOUND: { label: '候选', dot: 'bg-[#6fba2c]', badge: 'bg-[#eef7df] text-[#426a15]', group: 'result' },
  PLAN_STEP: { label: '步骤', dot: 'bg-[#19c8b9]', badge: 'bg-[#e6f9f6] text-[#0f766e]', group: 'result' },
  SEGMENT_PLANNED: { label: '片段', dot: 'bg-[#19c8b9]', badge: 'bg-[#e6f9f6] text-[#0f766e]', group: 'result' },
  CONFLICT_DETECTED: { label: '冲突', dot: 'bg-[#fc736d]', badge: 'bg-[#ffe2df] text-[#a73530]', group: 'result' },
  REPAIR_OPTIONS_READY: { label: '修复项', dot: 'bg-[#f5c31c]', badge: 'bg-[#fff4c2] text-[#7a5a00]', group: 'result' },
  PLAN_ASSEMBLED: { label: '组装', dot: 'bg-[#6fba2c]', badge: 'bg-[#eef7df] text-[#426a15]', group: 'result' },
  PLAN_FINISHED: { label: '完成', dot: 'bg-[#6fba2c]', badge: 'bg-[#eef7df] text-[#426a15]', group: 'terminal' },
  BACKEND_NOTICE: { label: '后台提示', dot: 'bg-[#f5c31c]', badge: 'bg-[#fff4c2] text-[#7a5a00]', group: 'system' },
  FINISH: { label: '结束', dot: 'bg-[#6fba2c]', badge: 'bg-[#eef7df] text-[#426a15]', group: 'terminal' },
  PLAN_FAILED: { label: '失败', dot: 'bg-[#e05a5a]', badge: 'bg-[#ffe2df] text-[#a73530]', group: 'terminal' },
  ERROR: { label: '错误', dot: 'bg-[#e05a5a]', badge: 'bg-[#ffe2df] text-[#a73530]', group: 'terminal' },
}

const CORE_TYPES = new Set([
  'START',
  'PLAN_STARTED',
  'INTENT',
  'INTENT_EXTRACTED',
  'WEATHER_CHECKED',
  'CANDIDATES_SEARCHING',
  'CANDIDATES_FOUND',
  'SEGMENT_PLANNED',
  'PLAN_STEP',
  'PLAN_ASSEMBLED',
  'PLAN_FINISHED',
  'FINISH',
  'ERROR',
  'PLAN_FAILED',
  'BACKEND_NOTICE',
])

const PROBLEM_TYPES = new Set(['ERROR', 'PLAN_FAILED', 'CONFLICT_DETECTED', 'REPAIR_OPTIONS_READY'])

const PHASES = [
  { type: 'INTENT_EXTRACTED', fallback: 'INTENT', label: '理解需求' },
  { type: 'WEATHER_CHECKED', label: '天气约束' },
  { type: 'CANDIDATES_FOUND', fallback: 'CANDIDATES_SEARCHING', label: '候选检索' },
  { type: 'SEGMENT_PLANNED', fallback: 'PLAN_STEP', label: '片段排程' },
  { type: 'PLAN_ASSEMBLED', fallback: 'PLAN_FINISHED', label: '方案组装' },
]

function toneFor(type: string): EventTone {
  return EVENT_TONES[type] ?? {
    label: type.replaceAll('_', ' ').toLowerCase(),
    dot: 'bg-[#c4b89e]',
    badge: 'bg-[#efebe0] text-[#5f5344]',
    group: 'system',
  }
}

function formatClock(value?: number) {
  if (!value) return '--:--:--'
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(value)
}

function formatElapsed(start?: number, current?: number) {
  if (!start || !current) return '+0.0s'
  return `+${((current - start) / 1000).toFixed(1)}s`
}

function formatDuration(start?: number, end?: number) {
  if (!start || !end) return '0.0s'
  return `${Math.max(0, (end - start) / 1000).toFixed(1)}s`
}

function compactValue(value?: string | number | null) {
  if (value === undefined || value === null || value === '') return '未提供'
  return String(value)
}

function paceLabel(value?: AgentPlanIntent['pace']) {
  if (value === 'RELAXED') return '舒缓'
  if (value === 'COMPACT') return '紧凑'
  if (value === 'NORMAL') return '正常'
  return '未提供'
}

function budgetLabel(value?: AgentPlanIntent['budgetLevel']) {
  if (value === 'LOW') return '低'
  if (value === 'MEDIUM') return '中'
  if (value === 'HIGH') return '高'
  return '未提供'
}

function transportLabel(value?: AgentPlanIntent['preferredTransportMode']) {
  if (value === 'WALK') return '步行'
  if (value === 'DRIVE') return '驾车'
  if (value === 'PUBLIC_TRANSIT') return '公共交通'
  return '未提供'
}

function summarizeEvent(event: AgentPlanStreamEvent) {
  const chips: string[] = []
  if (event.type === 'BACKEND_NOTICE' && event.status) chips.push(event.status)
  if (event.timeline?.length) chips.push(`${event.timeline.length} steps`)
  if (event.actionCard?.options?.length) chips.push(`${event.actionCard.options.length} actions`)
  if (event.conflicts?.length) chips.push(`${event.conflicts.length} conflicts`)
  if (event.repairOptions?.length) chips.push(`${event.repairOptions.length} repairs`)
  if (event.executionStatus) chips.push(event.executionStatus)
  if (event.planStatus) chips.push(event.planStatus)
  return chips
}

export function DevColumn({ plan, nodes, events, onClearEvents }: DevColumnProps) {
  const consoleBodyRef = useRef<HTMLDivElement>(null)
  const [filter, setFilter] = useState<EventFilter>('all')
  const [autoScroll, setAutoScroll] = useState(true)

  useEffect(() => {
    if (autoScroll && consoleBodyRef.current) {
      consoleBodyRef.current.scrollTop = consoleBodyRef.current.scrollHeight
    }
  }, [autoScroll, events])

  const firstReceivedAt = events[0]?.receivedAt
  const latestEvent = events[events.length - 1]
  const latestReceivedAt = latestEvent?.receivedAt
  const hasError = events.some((event) => event.type === 'ERROR' || event.type === 'PLAN_FAILED')
  const isFinished = Boolean(latestEvent && ['FINISH', 'PLAN_FINISHED', 'ERROR', 'PLAN_FAILED'].includes(latestEvent.type))
  const intent = plan?.intent
  const timelineCount = plan?.timeline?.length || nodes.length

  const counts = useMemo(() => {
    return events.reduce(
      (acc, event) => {
        const tone = toneFor(event.type)
        acc[tone.group] += 1
        if (event.type === 'BACKEND_NOTICE') acc.backend += 1
        if (PROBLEM_TYPES.has(event.type) || (event.type === 'BACKEND_NOTICE' && event.status !== 'INFO')) acc.problems += 1
        return acc
      },
      { system: 0, intent: 0, thinking: 0, tool: 0, result: 0, terminal: 0, problems: 0, backend: 0 },
    )
  }, [events])

  const filteredEvents = useMemo(() => {
    if (filter === 'core') return events.filter((event) => CORE_TYPES.has(event.type))
    if (filter === 'problems') return events.filter((event) => PROBLEM_TYPES.has(event.type) || (event.type === 'BACKEND_NOTICE' && event.status !== 'INFO'))
    return events
  }, [events, filter])

  const status = (() => {
    if (hasError) return { label: '异常', className: 'bg-[#ffe2df] text-[#a73530]' }
    if (isFinished) return { label: '已完成', className: 'bg-[#eef7df] text-[#426a15]' }
    if (events.length) return { label: '流式运行中', className: 'bg-[#e6f9f6] text-[#0f766e]' }
    return { label: '等待请求', className: 'bg-[#efebe0] text-[#725d42]' }
  })()

  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-y-auto overscroll-contain custom-scrollbar pb-[100px] md:pb-0 gap-4 p-4 bg-[#f7f3df]">
      <section className="shrink-0 rounded-[24px] border-2 border-[#c4b89e] bg-[#fff9e8] px-4 py-3 text-[#725d42] shadow-[0_3px_0_0_#d4c9b4]">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="m-0 text-[11px] font-black uppercase text-[#9a835a]">Agent Runtime</p>
            <h3 className="m-0 mt-0.5 text-[18px] font-black leading-tight text-[#794f27]">SSE 思考控制台</h3>
          </div>
          <span className={`shrink-0 rounded-full px-2.5 py-1 text-[11px] font-black ${status.className}`}>
            {status.label}
          </span>
        </div>

        <div className="mt-3 grid grid-cols-2 gap-2 text-xs md:grid-cols-5">
          <Metric label="事件" value={events.length} />
          <Metric label="耗时" value={formatDuration(firstReceivedAt, latestReceivedAt)} />
          <Metric label="步骤" value={timelineCount} />
          <Metric label="问题" value={counts.problems} tone={counts.problems ? 'danger' : 'normal'} />
          <Metric label="后台提示" value={counts.backend} />
        </div>

        <div className="mt-3 grid grid-cols-1 gap-2 text-xs">
          <KeyValue label="Plan ID" value={plan?.planId || latestEvent?.planId || '未创建'} />
          <KeyValue label="执行状态" value={plan?.executionStatus || latestEvent?.executionStatus || plan?.planStatus || '未开始'} />
          <KeyValue label="最后事件" value={latestEvent ? `${toneFor(latestEvent.type).label} · ${formatClock(latestReceivedAt)}` : '暂无'} />
        </div>
      </section>

      <section className="shrink-0 rounded-[24px] border-2 border-[#c4b89e] bg-[#fff9e8] px-4 py-3 text-[#725d42] shadow-[0_3px_0_0_#d4c9b4]">
        <div className="mb-3 flex items-center justify-between gap-3">
          <h3 className="m-0 text-[15px] font-black text-[#794f27]">结构化意图</h3>
          {intent?.isConsultingMode && (
            <span className="rounded-full bg-[#edf0ff] px-2 py-0.5 text-[10px] font-black text-[#4054a8]">咨询模式</span>
          )}
        </div>

        {intent ? (
          <div className="grid grid-cols-2 gap-2 text-xs">
            <IntentTile label="场景" value={compactValue(intent.sceneType)} />
            <IntentTile label="人数" value={`${compactValue(intent.headcount)} 人`} />
            <IntentTile label="时间" value={`${compactValue(intent.startTime)} - ${compactValue(intent.endTime)}`} />
            <IntentTile label="时长" value={`${compactValue(intent.totalMinutes)} 分钟`} />
            <IntentTile label="节奏" value={paceLabel(intent.pace)} />
            <IntentTile label="预算" value={budgetLabel(intent.budgetLevel)} />
            <IntentTile label="交通" value={transportLabel(intent.preferredTransportMode)} />
            <IntentTile label="天气敏感" value={intent.weatherSensitive ? '是' : '否'} />
            {intent.mustHave?.length > 0 && <TagRow label="必须包含" values={intent.mustHave} />}
            {intent.avoid?.length > 0 && <TagRow label="规避" values={intent.avoid} tone="warning" />}
            {intent.dietaryConstraints?.length > 0 && <TagRow label="饮食约束" values={intent.dietaryConstraints} />}
          </div>
        ) : (
          <div className="rounded-[18px] border-2 border-dashed border-[#d4c9b4] bg-[#efebe0] p-5 text-center text-xs font-bold text-[#9a835a]">
            发起规划后，这里会显示后端抽取到的时间、人数、预算、偏好和约束。
          </div>
        )}
      </section>

      <section className="shrink-0 rounded-[24px] border-2 border-[#c4b89e] bg-[#fff9e8] px-4 py-3 text-[#725d42] shadow-[0_3px_0_0_#d4c9b4]">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="m-0 text-[15px] font-black text-[#794f27]">规划阶段</h3>
          <span className="text-[11px] font-black text-[#9a835a]">{counts.tool + counts.result} 条工具/结果事件</span>
        </div>
        <div className="grid grid-cols-5 gap-1.5">
          {PHASES.map((phase) => {
            const matchedIndex = events.findIndex((event) => event.type === phase.type || event.type === phase.fallback)
            const done = matchedIndex >= 0
            const current = !isFinished && done && matchedIndex === events.findLastIndex((event) => PHASES.some((item) => event.type === item.type || event.type === item.fallback))
            return (
              <div key={phase.label} className="min-w-0">
                <div
                  className={`h-2 rounded-full ${
                    done ? (current ? 'bg-[#f7cd67]' : 'bg-[#19c8b9]') : 'bg-[#d4c9b4]'
                  }`}
                />
                <p className={`m-0 mt-1 truncate text-center text-[10px] font-black ${done ? 'text-[#725d42]' : 'text-[#b8aa91]'}`}>
                  {phase.label}
                </p>
              </div>
            )
          })}
        </div>
      </section>

      <section className="flex min-h-[360px] flex-1 flex-col overflow-hidden rounded-[24px] border-2 border-[#3d3028] bg-[#2b2118] text-[#e8d5bc] shadow-inner">
        <div className="shrink-0 border-b border-[#4a382b] bg-[#241b14] px-3 py-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <span className="h-2.5 w-2.5 rounded-full bg-[#ff5f56]" />
              <span className="h-2.5 w-2.5 rounded-full bg-[#ffbd2e]" />
              <span className="h-2.5 w-2.5 rounded-full bg-[#27c93f]" />
              <span className="ml-1 text-[11px] font-black text-[#e8d5bc]">Event Stream</span>
            </div>
            <div className="flex flex-wrap items-center gap-1.5">
              {(['all', 'core', 'problems'] as const).map((item) => (
                <button
                  key={item}
                  type="button"
                  className={`rounded-full border px-2 py-1 text-[10px] font-black transition-all ${
                    filter === item
                      ? 'border-[#f7cd67] bg-[#f7cd67] text-[#3d3028]'
                      : 'border-[#5b4636] bg-[#33271e] text-[#bdaea0] hover:border-[#8a6b52]'
                  }`}
                  onClick={() => setFilter(item)}
                >
                  {item === 'all' ? '全部' : item === 'core' ? '核心' : '问题'}
                </button>
              ))}
              <button
                type="button"
                className={`rounded-full border px-2 py-1 text-[10px] font-black transition-all ${
                  autoScroll
                    ? 'border-[#82d5bb] bg-[#82d5bb] text-[#143b35]'
                    : 'border-[#5b4636] bg-[#33271e] text-[#bdaea0] hover:border-[#8a6b52]'
                }`}
                onClick={() => setAutoScroll((value) => !value)}
              >
                自动滚动
              </button>
              {onClearEvents && (
                <button
                  type="button"
                  className="rounded-full border border-[#5b4636] bg-[#33271e] px-2 py-1 text-[10px] font-black text-[#bdaea0] transition-all hover:border-[#fc736d] hover:text-[#ffd4d1]"
                  onClick={onClearEvents}
                >
                  清空
                </button>
              )}
            </div>
          </div>
        </div>

        <div ref={consoleBodyRef} className="custom-scrollbar flex-1 overflow-y-auto bg-[#2b2118] px-3 py-3">
          {filteredEvents.length > 0 ? (
            <div className="relative pl-4">
              <div className="absolute bottom-2 left-[5px] top-2 w-px bg-[#5b4636]" />
              {filteredEvents.map((event, index) => {
                const tone = toneFor(event.type)
                const chips = summarizeEvent(event)
                return (
                  <article key={`${event.receivedAt || index}-${event.type}-${index}`} className="relative pb-3 last:pb-0">
                    <span className={`absolute -left-[15px] top-1.5 h-2.5 w-2.5 rounded-full ring-4 ring-[#2b2118] ${tone.dot}`} />
                    <div className="rounded-[16px] border border-[#4a382b] bg-[#33271e] px-3 py-2 shadow-[0_2px_0_0_#1b140f]">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="text-[10px] font-black text-[#8f7d6d]">#{events.indexOf(event) + 1}</span>
                        <span className={`rounded-full px-2 py-0.5 text-[10px] font-black ${tone.badge}`}>{tone.label}</span>
                        <span className="text-[10px] font-black text-[#8ab8e0]">Step {event.step}</span>
                        <span className="text-[10px] font-bold text-[#8f7d6d]">{formatElapsed(firstReceivedAt, event.receivedAt)}</span>
                        <span className="text-[10px] font-bold text-[#8f7d6d]">{formatClock(event.receivedAt)}</span>
                      </div>
                      <p className="m-0 mt-1.5 whitespace-pre-wrap break-words text-[12px] font-semibold leading-relaxed text-[#e8d5bc]">
                        {event.content || event.summary || '无事件内容'}
                      </p>
                      {chips.length > 0 && (
                        <div className="mt-2 flex flex-wrap gap-1">
                          {chips.map((chip) => (
                            <span key={chip} className="rounded-full bg-[#241b14] px-2 py-0.5 text-[10px] font-black text-[#cdb79f]">
                              {chip}
                            </span>
                          ))}
                        </div>
                      )}
                      {event.degradationNote && (
                        <div className="mt-2 rounded-[12px] border-l-4 border-[#f5c31c] bg-[#4a3717] px-2 py-1.5 text-[11px] font-bold text-[#ffe6a3]">
                          降级说明：{event.degradationNote}
                        </div>
                      )}
                    </div>
                  </article>
                )
              })}
            </div>
          ) : (
            <div className="flex h-full min-h-[260px] flex-col items-center justify-center rounded-[18px] border-2 border-dashed border-[#4a382b] p-6 text-center">
              <p className="m-0 text-[13px] font-black text-[#e8d5bc]">暂无可显示事件</p>
              <p className="m-0 mt-1 max-w-[260px] text-[11px] font-bold leading-relaxed text-[#8f7d6d]">
                发起规划后，SSE 的思考、工具调用、候选检索和最终组装过程会实时出现在这里。
              </p>
            </div>
          )}
        </div>
      </section>
    </div>
  )
}

function Metric({ label, value, tone = 'normal' }: { label: string; value: string | number; tone?: 'normal' | 'danger' }) {
  return (
    <div className="rounded-[16px] bg-[#efebe0] px-3 py-2">
      <p className="m-0 text-[10px] font-black text-[#9a835a]">{label}</p>
      <p className={`m-0 mt-0.5 truncate text-[16px] font-black ${tone === 'danger' ? 'text-[#a73530]' : 'text-[#725d42]'}`}>
        {value}
      </p>
    </div>
  )
}

function KeyValue({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex min-w-0 items-center justify-between gap-3 rounded-[14px] bg-[#f7f3df] px-3 py-1.5">
      <span className="shrink-0 text-[11px] font-black text-[#9a835a]">{label}</span>
      <span className="min-w-0 truncate text-right text-[11px] font-black text-[#725d42]">{value}</span>
    </div>
  )
}

function IntentTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0 rounded-[16px] bg-[#efebe0] px-3 py-2">
      <p className="m-0 text-[10px] font-black text-[#9a835a]">{label}</p>
      <p className="m-0 mt-0.5 truncate text-[12px] font-black text-[#725d42]">{value}</p>
    </div>
  )
}

function TagRow({ label, values, tone = 'normal' }: { label: string; values: string[]; tone?: 'normal' | 'warning' }) {
  return (
    <div className="col-span-2 rounded-[16px] bg-[#efebe0] px-3 py-2">
      <p className="m-0 text-[10px] font-black text-[#9a835a]">{label}</p>
      <div className="mt-1 flex flex-wrap gap-1">
        {values.map((value) => (
          <span
            key={value}
            className={`rounded-full px-2 py-0.5 text-[10px] font-black ${
              tone === 'warning' ? 'bg-[#ffe6d8] text-[#a84612]' : 'bg-[#e6f9f6] text-[#0f766e]'
            }`}
          >
            {value}
          </span>
        ))}
      </div>
    </div>
  )
}
