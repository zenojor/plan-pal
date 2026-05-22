import { Button } from 'animal-island-ui'
import type { Stage } from '../types/plan'

type PlanningHeaderProps = {
  requirement: string
  stage: Stage
  onReset: () => void
  onConfirm: () => void
}

export function PlanningHeader({
  requirement,
  stage,
  onReset,
  onConfirm,
}: PlanningHeaderProps) {
  return (
    <header className="shrink-0 relative z-20 flex items-center justify-between gap-3.5 px-4 md:px-[40px] py-3 md:py-3.5 bg-animal-bg/90 border-b border-animal-border-light md:border-b-2 backdrop-blur-md">
      <div className="min-w-0 flex-1 mr-2">
        <strong className="block text-[#794f27] text-[19px] md:text-[21px] font-black">Recommended plan</strong>
        <span className="block max-w-[720px] mt-0.5 text-[#725d42] text-[12px] md:text-[13px] font-bold overflow-hidden text-ellipsis whitespace-nowrap">
          {requirement}
        </span>
      </div>
      <div className="hidden md:flex items-center gap-2">
        <Button type="default" size="small" onClick={onReset}>
          Reset
        </Button>
      </div>
      <div className="flex md:hidden items-center gap-2.5 shrink-0">
        <button
          type="button"
          onClick={onReset}
          className="flex items-center justify-center w-9 h-9 border-2 border-animal-border rounded-full bg-[#fff9e8] text-[#725d42] cursor-pointer shadow-[0_3px_0_0_#d4c9b4] active:translate-y-0.5 active:shadow-[0_1px_0_0_#d4c9b4] transition-all"
          title="Reset"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="3"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="w-[18px] h-[18px]"
          >
            <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" />
            <path d="M3 3v5h5" />
          </svg>
        </button>
        <button
          type="button"
          onClick={onConfirm}
          className={`flex items-center justify-center w-9 h-9 border-2 rounded-full cursor-pointer transition-all ${
            stage === 'confirmed'
              ? 'bg-[#4ca61c] border-[#3c8715] text-white shadow-[0_3px_0_0_#2b610f] active:translate-y-0.5 active:shadow-[0_1px_0_0_#2b610f]'
              : 'bg-[#6fba2c] border-[#5a9e1e] text-white shadow-[0_3px_0_0_#437916] active:translate-y-0.5 active:shadow-[0_1px_0_0_#437916]'
          }`}
          title={stage === 'confirmed' ? 'Confirmed' : 'Confirm'}
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="3.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="w-[18px] h-[18px]"
          >
            <polyline points="20 6 9 17 4 12" />
          </svg>
        </button>
      </div>
    </header>
  )
}
