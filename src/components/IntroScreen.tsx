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
    <main className="relative grid place-items-center min-h-screen p-8 max-[640px]:p-[18px] bg-animal-grid bg-animal-bg overflow-hidden before:content-[''] before:absolute before:inset-x-[-8%] before:-bottom-[18%] before:h-[36vh] before:bg-[#e6f9f6]/75 before:rounded-t-[50%] before:pointer-events-none">
      <div className="absolute top-[18px] left-1/2 z-10 flex items-center gap-2 px-2.5 py-[7px] border-2 border-animal-border/45 rounded-full bg-[#fff9e8]/86 text-[#794f27] font-black -translate-x-1/2">
        <span>Plan Pal</span>
        <button
          type="button"
          className="border-0 border-l-2 border-animal-border/45 pl-2 bg-transparent text-[#9f927d] cursor-pointer"
        >
          Demo
        </button>
      </div>

      <section className="relative z-10 grid justify-items-center w-full max-w-[820px] mt-[6vh]">
        <div
          className="grid place-items-center w-[42px] h-[42px] mb-4 rounded-full bg-[#f7cd67] text-[#794f27] text-2xl shadow-[0_5px_0_#dba90e]"
          aria-hidden="true"
        >
          *
        </div>
        <h1 className="mt-0 mb-[30px] text-center text-[#794f27] font-black text-[34px] max-[640px]:text-[34px] min-[641px]:text-[58px] leading-[1.05]">
          What should we plan today?
        </h1>

        <form
          className="w-full max-w-[760px] p-5 max-[640px]:p-4 border-2 border-animal-border rounded-[28px] max-[640px]:rounded-[24px] bg-[#f7f3df] shadow-[0_5px_0_0_#d4c9b4,0_16px_48px_rgba(61,52,40,0.12)]"
          onSubmit={onSubmit}
        >
          <textarea
            className="block w-full min-h-[96px] resize-none border-0 outline-none bg-transparent text-[#725d42] text-lg font-bold placeholder-[#9f927d]"
            value={draft}
            placeholder="Tell me the time, people, location preferences, and constraints..."
            onChange={(event) => onDraftChange(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                onSubmit(event)
              }
            }}
          />
          <div className="flex items-center justify-between gap-3">
            <button
              type="button"
              className="grid place-items-center w-9 h-9 border-2 border-animal-border rounded-full bg-[#fff9e8] text-[#725d42] text-2xl leading-none cursor-pointer"
              aria-label="Add attachment"
            >
              +
            </button>
            <div className="flex items-center gap-3">
              <span className="text-[#9f927d] text-[13px] font-black">Local plan</span>
              <Button htmlType="submit" type="primary" size="small" disabled={!draft.trim()}>
                Start
              </Button>
            </div>
          </div>
        </form>

        <div className="flex flex-wrap justify-center gap-2.5 mt-[18px]" aria-label="Quick prompts">
          {examplePrompts.map((prompt) => (
            <button
              type="button"
              key={prompt}
              className="max-w-[250px] max-[640px]:max-w-full px-3 py-2 border-2 border-animal-border rounded-full bg-[#fff9e8] text-[#725d42] text-[13px] font-extrabold whitespace-nowrap overflow-hidden text-ellipsis cursor-pointer transition-all duration-200 hover:-translate-y-[1px] hover:border-[#a89878]"
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
