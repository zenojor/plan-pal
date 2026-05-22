import { Button } from 'animal-island-ui'
import type { FormEvent } from 'react'

type IntroScreenProps = {
  draft: string
  examplePrompts: string[]
  onDraftChange: (value: string) => void
  onSubmit: (event?: FormEvent) => void
}

export function IntroScreen({
  draft,
  examplePrompts,
  onDraftChange,
  onSubmit,
}: IntroScreenProps) {
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
          *
        </div>
        <h1 className="w-full max-w-full mt-0 mb-[30px] max-[640px]:mb-6 text-center text-[#794f27] font-black text-[34px] max-[640px]:text-[clamp(30px,8.2vw,34px)] min-[641px]:text-[58px] leading-[1.12]">
          今天想把什么安排好？
        </h1>

        <form
          className="w-full max-w-[760px] min-w-0 box-border p-5 max-[640px]:w-full max-[640px]:max-w-[calc(100vw-32px)] max-[640px]:p-4 border-2 border-animal-border rounded-[28px] max-[640px]:rounded-[24px] bg-[#f7f3df] shadow-[0_5px_0_0_#d4c9b4,0_16px_48px_rgba(61,52,40,0.12)]"
          onSubmit={onSubmit}
        >
          <textarea
            className="block w-full min-w-0 min-h-[96px] resize-none border-0 outline-none bg-transparent text-[#725d42] text-lg max-[640px]:text-base font-bold placeholder-[#9f927d]"
            value={draft}
            placeholder="告诉我时间、人数、地点偏好和限制..."
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
              className="grid place-items-center w-9 h-9 border-2 border-animal-border rounded-full bg-[#fff9e8] text-[#725d42] text-2xl leading-none cursor-pointer"
              aria-label="添加附件"
            >
              +
            </button>
            <div className="flex min-w-0 flex-1 items-center justify-end gap-3">
              <span className="min-w-0 text-[#9f927d] text-[13px] font-black truncate">
                本地规划
              </span>
              <Button
                htmlType="submit"
                type="primary"
                size="small"
                className="shrink-0"
                disabled={!draft.trim()}
              >
                开始
              </Button>
            </div>
          </div>
        </form>

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
