import type { RefObject } from 'react'
import { columnMeta } from '../data/planData'
import type { ColumnId } from '../types/plan'

type ColumnPickerProps = {
  closedColumns: ColumnId[]
  isOpen: boolean
  onToggle: () => void
  onAddColumn: (column: ColumnId) => void
  containerRef: RefObject<HTMLDivElement | null>
}

export function ColumnPicker({
  closedColumns,
  isOpen,
  onToggle,
  onAddColumn,
  containerRef,
}: ColumnPickerProps) {
  if (closedColumns.length === 0) return null

  return (
    <div
      className="fixed top-1/2 right-[clamp(18px,4vw,42px)] z-[35] hidden md:block -translate-y-1/2"
      ref={containerRef}
    >
      {isOpen && (
        <div className="absolute top-1/2 right-[58px] flex flex-col gap-1 min-w-[124px] -translate-y-1/2 bg-[#ffeea0] border-2 border-animal-border rounded-[22px] shadow-[0_8px_20px_rgba(61,52,40,0.12)] p-2.5 animate-column-menu-pop">
          {closedColumns.map((column) => (
            <button
              type="button"
              key={column}
              className="flex items-center justify-center w-full min-h-[38px] px-4 py-1.5 text-animal-text-body font-black text-[15px] rounded-[14px] cursor-pointer transition-all hover:bg-[#fff9e8]/80 text-center whitespace-nowrap"
              onClick={() => onAddColumn(column)}
            >
              {columnMeta[column].title}
            </button>
          ))}
        </div>
      )}
      <button
        className="relative grid place-items-center w-[42px] h-[42px] border-2 border-animal-border rounded-[14px] bg-[#fff9e8] text-[#725d42] cursor-pointer shadow-[0_5px_0_0_#d4c9b4,0_12px_28px_rgba(61,52,40,0.16)] transition-all duration-200 hover:-translate-y-[1px] hover:shadow-[0_6px_0_0_#d4c9b4,0_14px_32px_rgba(61,52,40,0.18)] active:translate-y-0.5 active:shadow-[0_2px_0_0_#d4c9b4]"
        type="button"
        aria-label="Add column"
        aria-expanded={isOpen}
        onClick={onToggle}
      >
        <svg
          className="w-[31px] h-[31px] text-[#725d42] overflow-visible"
          viewBox="0 0 40 40"
          role="img"
          aria-hidden="true"
        >
          <path
            d="M23 9.5h6.5c3 0 5 2 5 5v11c0 3-2 5-5 5H23c-3 0-5-2-5-5v-11c0-3 2-5 5-5Z"
            fill="none"
            stroke="currentColor"
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth="3"
          />
          <path
            d="M25 16v8"
            fill="none"
            stroke="currentColor"
            strokeLinecap="round"
            strokeWidth="3"
          />
          <path
            d="M7 20h10M12 15v10"
            fill="none"
            stroke="currentColor"
            strokeLinecap="round"
            strokeWidth="3.2"
          />
        </svg>
      </button>
    </div>
  )
}
