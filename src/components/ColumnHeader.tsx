import { columnMeta } from '../data/planData'
import type { ColumnId } from '../types/plan'

type ColumnHeaderProps = {
  column: ColumnId
  onDragEnd: () => void
  onDragStart: () => void
  onRemove: (column: ColumnId) => void
}

export function ColumnHeader({
  column,
  onDragEnd,
  onDragStart,
  onRemove,
}: ColumnHeaderProps) {
  return (
    <div
      className="hidden md:flex items-end justify-between gap-4 min-h-[70px] px-6 py-2.5 cursor-grab active:cursor-grabbing select-none"
      draggable={true}
      onDragEnd={onDragEnd}
      onDragStart={onDragStart}
    >
      <div>
        <span className="hidden md:inline-flex items-center min-h-[21px] px-2 rounded-full bg-[#e6f9f6] text-[#11a89b] text-[11px] font-black">Drag to reorder</span>
        <h2 className="m-0 text-[#794f27] text-[23px] leading-tight font-black">
          {columnMeta[column].title}
        </h2>
        <p className="hidden m-1 text-[#9f927d] text-[13px] font-bold">{columnMeta[column].hint}</p>
      </div>
      {column !== 'puzzle' && (
        <button
          type="button"
          className="hidden md:block shrink-0 px-2 py-1.25 border-2 border-animal-border rounded-full bg-[#fff9e8] text-[#725d42] text-[13px] font-black cursor-pointer hover:-translate-y-0.5 hover:border-[#a89878] transition-all"
          onClick={() => onRemove(column)}
        >
          Close
        </button>
      )}
    </div>
  )
}
