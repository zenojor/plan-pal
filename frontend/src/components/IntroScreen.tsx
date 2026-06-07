import { Button } from 'animal-island-ui'
import type { FormEvent } from 'react'
import { useState } from 'react'

type QuickTimeRange = {
  start: number
  end: number
}

type QuickHeadcount = '' | '1' | '2' | '3' | '4+'
type QuickLocationScope = 'nearby' | 'business' | 'flexible'
type QuickPace = 'relaxed' | 'normal' | 'compact'

type QuickOption<T extends string> = {
  value: T
  label: string
  prompt: string
}

const headcountOptions: QuickOption<Exclude<QuickHeadcount, ''>>[] = [
  { value: '1', label: '1 人', prompt: '1 个人' },
  { value: '2', label: '2 人', prompt: '2 个人' },
  { value: '3', label: '3 人', prompt: '3 个人' },
  { value: '4+', label: '4+ 人', prompt: '4 个人以上' },
]

const locationOptions: QuickOption<QuickLocationScope>[] = [
  { value: 'nearby', label: '就近安排', prompt: '优先附近少绕路' },
  { value: 'business', label: '指定商圈', prompt: '按我补充的商圈或地点范围安排' },
  { value: 'flexible', label: '范围放宽', prompt: '找不到合适候选时可以扩大范围' },
]

const paceOptions: QuickOption<QuickPace>[] = [
  { value: 'relaxed', label: '轻松一点', prompt: '节奏轻松，少排队少折腾' },
  { value: 'normal', label: '正常安排', prompt: '时间利用和舒适度平衡' },
  { value: 'compact', label: '多安排一点', prompt: '可以更紧凑，多安排一个点' },
]

type IntroScreenProps = {
  draft: string
  examplePrompts: string[]
  isSubmitting: boolean
  onDraftChange: (value: string) => void
  onSubmit: (event?: FormEvent, customText?: string) => void
  submitError: string | null
  submitTarget: string
}

export function IntroScreen({
  draft,
  examplePrompts,
  isSubmitting,
  onDraftChange,
  onSubmit,
  submitError,
  submitTarget,
}: IntroScreenProps) {
  const [quickPlanOpen, setQuickPlanOpen] = useState(false)
  const [quickTimeRange, setQuickTimeRange] = useState<QuickTimeRange>({ start: 14, end: 18 })
  const [quickHeadcount, setQuickHeadcount] = useState<QuickHeadcount>('')
  const [quickLocationScope, setQuickLocationScope] = useState<QuickLocationScope>('nearby')
  const [quickPace, setQuickPace] = useState<QuickPace>('relaxed')
  const [quickExtra, setQuickExtra] = useState('')

  const timeRangeInputClass = [
    'absolute inset-0 m-0 h-11 w-full appearance-none bg-transparent pointer-events-none cursor-grab active:cursor-grabbing disabled:cursor-not-allowed',
    '[&::-webkit-slider-runnable-track]:h-11 [&::-webkit-slider-runnable-track]:bg-transparent',
    '[&::-webkit-slider-thumb]:pointer-events-auto [&::-webkit-slider-thumb]:h-7 [&::-webkit-slider-thumb]:w-7 [&::-webkit-slider-thumb]:appearance-none',
    '[&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:border-4 [&::-webkit-slider-thumb]:border-[#fff9e8] [&::-webkit-slider-thumb]:bg-[#19c8b9]',
    '[&::-webkit-slider-thumb]:shadow-[0_4px_0_0_#11a89b,0_0_0_2px_#725d42] [&::-webkit-slider-thumb]:transition-all [&::-webkit-slider-thumb]:duration-150',
    'hover:[&::-webkit-slider-thumb]:-translate-y-0.5 hover:[&::-webkit-slider-thumb]:bg-[#3dd4c6]',
    'active:[&::-webkit-slider-thumb]:translate-y-0.5 active:[&::-webkit-slider-thumb]:shadow-[0_2px_0_0_#11a89b,0_0_0_2px_#725d42]',
    'focus-visible:[&::-webkit-slider-thumb]:outline-3 focus-visible:[&::-webkit-slider-thumb]:outline-offset-3 focus-visible:[&::-webkit-slider-thumb]:outline-[#ffcc00]',
    '[&::-moz-range-track]:h-11 [&::-moz-range-track]:bg-transparent',
    '[&::-moz-range-thumb]:pointer-events-auto [&::-moz-range-thumb]:h-5 [&::-moz-range-thumb]:w-5 [&::-moz-range-thumb]:rounded-full',
    '[&::-moz-range-thumb]:border-4 [&::-moz-range-thumb]:border-[#fff9e8] [&::-moz-range-thumb]:bg-[#19c8b9]',
    '[&::-moz-range-thumb]:shadow-[0_4px_0_0_#11a89b,0_0_0_2px_#725d42] [&::-moz-range-thumb]:transition-all [&::-moz-range-thumb]:duration-150',
    'hover:[&::-moz-range-thumb]:-translate-y-0.5 hover:[&::-moz-range-thumb]:bg-[#3dd4c6]',
    'active:[&::-moz-range-thumb]:translate-y-0.5 active:[&::-moz-range-thumb]:shadow-[0_2px_0_0_#11a89b,0_0_0_2px_#725d42]',
    'focus-visible:[&::-moz-range-thumb]:outline-3 focus-visible:[&::-moz-range-thumb]:outline-offset-3 focus-visible:[&::-moz-range-thumb]:outline-[#ffcc00]',
  ].join(' ')

  function clampHour(value: number) {
    return Math.max(0, Math.min(24, value))
  }

  function formatHourLabel(hour: number) {
    const whole = Math.floor(hour)
    const minutes = Math.round((hour - whole) * 60)
    return `${whole.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}`
  }

  function optionClass(active: boolean) {
    return [
      'min-h-10 px-3 py-2.5 rounded-[16px] border-2 text-xs font-black cursor-pointer transition-all duration-150 disabled:cursor-not-allowed disabled:opacity-50',
      active
        ? 'border-[#11a89b] bg-[#e6f9f6] text-[#0f4c46] shadow-[0_3px_0_0_#11a89b]'
        : 'border-[#c4b89e] bg-[#fff9e8] text-[#725d42] shadow-[0_3px_0_0_#d4c9b4] hover:-translate-y-0.5 hover:border-[#a89878] hover:bg-[#ffeea0] active:translate-y-[1px] active:shadow-[0_1px_0_0_#d4c9b4]',
    ].join(' ')
  }

  function sectionTitle(label: string) {
    return (
      <div className="flex items-center gap-2 text-xs font-black text-[#794f27]">
        <span className="h-2.5 w-2.5 rounded-full border-2 border-[#11a89b] bg-[#e6f9f6] shadow-[0_1px_0_0_#11a89b]" />
        <span>{label}</span>
      </div>
    )
  }

  function quickPrompt() {
    const topic = draft.trim() || '安排吃饭和轻活动'
    const headcount = headcountOptions.find((option) => option.value === quickHeadcount)?.prompt
    const location = locationOptions.find((option) => option.value === quickLocationScope)?.prompt
    const pace = paceOptions.find((option) => option.value === quickPace)?.prompt
    return [
      `今天 ${formatHourLabel(quickTimeRange.start)} 到 ${formatHourLabel(quickTimeRange.end)}`,
      headcount,
      topic,
      location,
      pace,
      quickExtra.trim(),
    ]
      .filter(Boolean)
      .join('，') + '。'
  }

  function submitQuickPlan() {
    if (!quickHeadcount || isSubmitting) return
    onSubmit(undefined, quickPrompt())
  }

  function handleQuickPlanClick() {
    if (isSubmitting) return
    if (!quickPlanOpen) {
      onDraftChange('')
      setQuickExtra('')
    }
    setQuickPlanOpen((open) => !open)
  }

  function renderTimeSlider() {
    const max = 24
    const left = (quickTimeRange.start / max) * 100
    const right = 100 - (quickTimeRange.end / max) * 100
    const startThumbRaised = quickTimeRange.start >= quickTimeRange.end - 1

    return (
      <div className="grid gap-2.5 rounded-[24px] border-[2.5px] border-[#c4b89e] bg-[#f7f3df] px-3.5 pt-3 pb-2.5 shadow-[0_3px_0_0_#d4c9b4,inset_0_1px_0_rgba(255,255,255,0.7)]">
        <div className="inline-flex w-fit items-center justify-self-center gap-2 rounded-full border-2 border-[#82d5bb] bg-[#e6f9f6] px-3.5 py-1 text-[13px] font-black text-[#725d42] shadow-[0_3px_0_0_rgba(17,168,155,0.28)]">
          <span>{formatHourLabel(quickTimeRange.start)}</span>
          <span className="text-[11px] text-[#11a89b]">到</span>
          <span>{formatHourLabel(quickTimeRange.end)}</span>
        </div>
        <div className="relative h-[58px] px-0.5">
          <div className="absolute inset-x-0 top-[18px] h-3.5 rounded-full border-2 border-[#c4b89e] bg-[radial-gradient(circle,rgba(114,93,66,0.32)_0_2px,transparent_2.5px),linear-gradient(180deg,#f0e8d8_0%,#eadfca_100%)] bg-[length:25%_100%,100%_100%] bg-repeat-x shadow-[inset_0_2px_3px_rgba(114,93,66,0.14)]" />
          <div
            className="absolute top-[18px] h-3.5 rounded-full border-2 border-[#11a89b] bg-gradient-to-b from-[#3dd4c6] to-[#19c8b9] shadow-[0_3px_0_0_#11a89b,inset_0_1px_0_rgba(255,255,255,0.5)]"
            style={{ left: `${left}%`, right: `${right}%` }}
          />
          <input
            type="range"
            aria-label="开始时间"
            aria-valuetext={`${formatHourLabel(quickTimeRange.start)} 开始`}
            min={0}
            max={24}
            step={0.5}
            disabled={isSubmitting}
            value={quickTimeRange.start}
            className={timeRangeInputClass}
            style={{ zIndex: startThumbRaised ? 4 : 3 }}
            onChange={(event) => {
              const nextStart = Math.min(clampHour(Number(event.target.value)), quickTimeRange.end - 0.5)
              setQuickTimeRange((current) => ({ ...current, start: nextStart }))
            }}
          />
          <input
            type="range"
            aria-label="结束时间"
            aria-valuetext={`${formatHourLabel(quickTimeRange.end)} 结束`}
            min={0}
            max={24}
            step={0.5}
            disabled={isSubmitting}
            value={quickTimeRange.end}
            className={timeRangeInputClass}
            style={{ zIndex: startThumbRaised ? 3 : 4 }}
            onChange={(event) => {
              const nextEnd = Math.max(clampHour(Number(event.target.value)), quickTimeRange.start + 0.5)
              setQuickTimeRange((current) => ({ ...current, end: nextEnd }))
            }}
          />
          <div className="absolute inset-x-0 bottom-0 flex justify-between text-[10px] font-black text-[#9f927d]">
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

  function renderQuickPlanPanel() {
    if (!quickPlanOpen) return null

    return (
      <div
        className="fixed inset-0 z-[100] overflow-y-auto bg-[rgba(61,52,40,0.28)] px-4 py-6 backdrop-blur-[2px] max-[640px]:py-4"
        onMouseDown={() => setQuickPlanOpen(false)}
      >
        <section
          id="quick-plan-panel"
          role="dialog"
          aria-modal="true"
          aria-labelledby="quick-plan-title"
          className="relative mx-auto w-full max-w-[760px] overflow-hidden rounded-[22px_26px_20px_24px/24px_20px_26px_22px] border-[2.5px] border-[#a89878]/75 bg-[#fffdf5] text-[#725d42] shadow-[0_5px_0_0_#d4c9b4,0_20px_56px_rgba(61,52,40,0.24)] animate-column-pop"
          onMouseDown={(event) => event.stopPropagation()}
        >
          <button
            type="button"
            aria-label="关闭快速计划"
            className="absolute right-3 top-3 z-10 grid h-9 w-9 place-items-center rounded-full border-2 border-[#c4b89e] bg-[#fff9e8] text-base font-black text-[#725d42] shadow-[0_3px_0_0_#d4c9b4] transition-all hover:-translate-y-0.5 hover:border-[#a89878] hover:bg-[#ffeea0] active:translate-y-[1px] active:shadow-[0_1px_0_0_#d4c9b4]"
            onClick={() => setQuickPlanOpen(false)}
          >
            ×
          </button>

          <div className="border-b-2 border-[#e5dac0] bg-[linear-gradient(135deg,#fff9e8_0%,#fff3c4_58%,#e6f9f6_100%)] px-4 py-3 pr-14">
            <span className="inline-flex w-fit rounded-full border-2 border-[#82d5bb] bg-[#e6f9f6] px-3 py-1 text-[11px] font-black uppercase tracking-wide text-[#0f766e] shadow-[0_2px_0_0_rgba(17,168,155,0.28)]">
              补充信息
            </span>
            <h2 id="quick-plan-title" className="m-0 mt-2 text-[15px] font-black leading-snug text-[#794f27]">
              补充出行信息
            </h2>
            <p className="m-0 mt-1 text-xs font-semibold leading-relaxed text-[#725d42]">
              填好后生成 3 个方向。
            </p>
          </div>

          <div className="flex flex-col gap-3.5 px-3.5 py-4">
            <div className="flex flex-col gap-2">
              {sectionTitle('出行时间段')}
              {renderTimeSlider()}
            </div>

            <div className="flex flex-col gap-2">
              {sectionTitle('出行人数')}
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                {headcountOptions.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    disabled={isSubmitting}
                    className={optionClass(quickHeadcount === option.value)}
                    onClick={() => setQuickHeadcount(option.value)}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex flex-col gap-2">
              {sectionTitle('地点范围')}
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
                {locationOptions.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    disabled={isSubmitting}
                    className={optionClass(quickLocationScope === option.value)}
                    onClick={() => setQuickLocationScope(option.value)}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex flex-col gap-2">
              {sectionTitle('活动节奏')}
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
                {paceOptions.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    disabled={isSubmitting}
                    className={optionClass(quickPace === option.value)}
                    onClick={() => setQuickPace(option.value)}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex flex-col gap-2">
              {sectionTitle('其它偏好')}
              <input
                type="text"
                disabled={isSubmitting}
                className="w-full box-border rounded-[18px] border-[2.5px] border-[#c4b89e] bg-[#f7f3df] px-3.5 py-2.5 text-xs font-bold text-[#725d42] shadow-[0_3px_0_0_#d4c9b4] outline-none transition-all placeholder-[#9f927d]/80 focus:border-[#ffcc00] focus:shadow-[0_3px_0_0_#e0b800,0_0_0_3px_rgba(255,204,0,0.15)] disabled:opacity-50"
                placeholder={
                  quickLocationScope === 'business'
                    ? '比如：静安寺、人民广场、家附近'
                    : '室内、少排队、带孩子、不能吃辣、预算低一点'
                }
                value={quickExtra}
                onChange={(event) => setQuickExtra(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && quickHeadcount) {
                    event.preventDefault()
                    submitQuickPlan()
                  }
                }}
              />
            </div>

            <Button
              htmlType="button"
              type="primary"
              disabled={isSubmitting || !quickHeadcount}
              className="mt-0.5 w-full bg-[#ffcc00]! border-[#ffcc00]! text-[#725d42]! shadow-[0_4px_0_0_#dba90e]! hover:-translate-y-0.5 active:translate-y-[1px] active:shadow-[0_1px_0_0_#dba90e]! transition-all duration-150 font-black text-sm py-2.5! h-auto! rounded-[18px]!"
              onClick={submitQuickPlan}
            >
              {isSubmitting ? '生成中...' : '生成三条方案'}
            </Button>
          </div>
        </section>
      </div>
    )
  }

  return (
    <main className="relative grid place-items-center w-full max-w-full min-h-[100svh] box-border p-8 max-[640px]:px-4 max-[640px]:py-6 bg-animal-grid bg-animal-bg overflow-hidden before:content-[''] before:absolute before:inset-x-[-8%] before:-bottom-[18%] before:h-[36vh] before:bg-[#e6f9f6]/75 before:rounded-t-[50%] before:pointer-events-none">
      <div className="absolute top-[18px] left-1/2 z-10 flex items-center gap-2 px-2.5 py-[7px] border-2 border-animal-border/45 rounded-full bg-[#fff9e8]/86 text-[#794f27] font-black -translate-x-1/2">
        <span>Plan Pal</span>
        <button
          type="button"
          className="border-0 border-l-2 border-animal-border/45 pl-2 bg-transparent text-[#9f927d] cursor-pointer"
        >
          Demo
        </button>
      </div>

      <section className="relative z-10 grid justify-items-center w-full max-w-[820px] min-w-0 mt-[6vh] max-[640px]:mt-[74px]">
        <div
          className="grid place-items-center w-[42px] h-[42px] mb-4 rounded-full bg-[#f7cd67] text-[#794f27] text-2xl shadow-[0_5px_0_#dba90e]"
          aria-hidden="true"
        >
          :D
        </div>
        <h1 className="w-full max-w-full mt-0 mb-[30px] max-[640px]:mb-6 text-center text-[#794f27] font-black text-[34px] max-[640px]:text-[clamp(30px,8.2vw,34px)] min-[641px]:text-[58px] leading-[1.12]">
          今天想把什么安排好？
        </h1>

        {renderQuickPlanPanel()}

        <form
          className="w-full max-w-[760px] min-w-0 box-border p-5 max-[640px]:w-full max-[640px]:max-w-[calc(100vw-32px)] max-[640px]:p-4 border-2 border-animal-border rounded-[28px] max-[640px]:rounded-[24px] bg-[#f7f3df] shadow-[0_5px_0_0_#d4c9b4,0_16px_48px_rgba(61,52,40,0.12)]"
          onSubmit={onSubmit}
        >
          <textarea
            className="block w-full min-w-0 min-h-[96px] resize-none border-0 outline-none bg-transparent text-[#725d42] text-lg max-[640px]:text-base font-bold placeholder-[#9f927d]"
            value={draft}
            placeholder="告诉我想安排什么，或者点快速计划补齐信息..."
            onChange={(event) => onDraftChange(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                onSubmit(event)
              }
            }}
          />
          <div className="flex flex-wrap items-center justify-between gap-3">
            <button
              type="button"
              className={[
                'min-h-9 rounded-full border-2 px-3.5 text-[12px] font-black transition-all duration-150 hover:-translate-y-0.5 active:translate-y-[1px] disabled:cursor-not-allowed disabled:opacity-50',
                quickPlanOpen
                  ? 'border-[#11a89b] bg-[#e6f9f6] text-[#0f4c46] shadow-[0_3px_0_0_#11a89b] active:shadow-[0_1px_0_0_#11a89b]'
                  : 'border-animal-border bg-[#fff9e8] text-[#725d42] shadow-[0_3px_0_0_#d4c9b4] active:shadow-[0_1px_0_0_#d4c9b4]',
              ].join(' ')}
              aria-expanded={quickPlanOpen}
              aria-controls="quick-plan-panel"
              disabled={isSubmitting}
              onClick={handleQuickPlanClick}
            >
              快速计划
            </button>
            <div className="flex min-w-0 flex-1 items-center justify-end gap-3">
              <span className="min-w-0 text-[#9f927d] text-[13px] font-black truncate">
                API: {submitTarget}
              </span>
              <Button
                htmlType="submit"
                type="primary"
                size="small"
                className="shrink-0"
                disabled={!draft.trim() || isSubmitting}
              >
                {isSubmitting ? '规划中...' : '开始'}
              </Button>
            </div>
          </div>

        </form>

        {submitError ? (
          <p className="w-full max-w-[760px] mt-3 mb-0 px-4 text-[#b44d2f] text-sm font-black text-center">
            {submitError}
          </p>
        ) : null}

        <div
          className="flex w-full max-w-[760px] min-w-0 flex-wrap justify-center gap-2.5 mt-[18px] max-[640px]:max-w-[calc(100vw-32px)]"
          aria-label="快捷提示"
        >
          {examplePrompts.map((prompt) => (
            <button
              type="button"
              key={prompt}
              className="w-full min-w-0 min-h-[40px] max-w-[250px] max-[640px]:max-w-full px-3 py-2 border-2 border-animal-border rounded-full bg-[#fff9e8] text-[#725d42] text-[13px] font-extrabold whitespace-nowrap overflow-hidden text-ellipsis cursor-pointer transition-all duration-200 hover:-translate-y-[1px] hover:border-[#a89878]"
              onClick={() => onDraftChange(prompt)}
            >
              {prompt}
            </button>
          ))}
        </div>
      </section>
    </main>
  )
}
