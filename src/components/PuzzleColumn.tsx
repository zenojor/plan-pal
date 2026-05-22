import { Button, Card, Input } from 'animal-island-ui'
import type { PlanNode } from '../types/plan'

type PuzzleColumnProps = {
  draggingNodeId: string | null
  dragOverNodeId: string | null
  editingNodeId: string | null
  nodeDraft: string
  nodes: PlanNode[]
  onApplyRewrite: (nodeId: string) => void
  onDragEnd: () => void
  onDragStart: (nodeId: string) => void
  onDrop: (nodeId: string) => void
  onEdit: (nodeId: string) => void
  onMoveDown: (nodeId: string) => void
  onMoveUp: (nodeId: string) => void
  onOpenMerchant: (place: string) => void
  onReplace: (nodeId: string) => void
  onSetDragOverNodeId: (nodeId: string | null) => void
  onSetNodeDraft: (value: string) => void
}

export function PuzzleColumn({
  draggingNodeId,
  dragOverNodeId,
  editingNodeId,
  nodeDraft,
  nodes,
  onApplyRewrite,
  onDragEnd,
  onDragStart,
  onDrop,
  onEdit,
  onMoveDown,
  onMoveUp,
  onOpenMerchant,
  onReplace,
  onSetDragOverNodeId,
  onSetNodeDraft,
}: PuzzleColumnProps) {
  return (
    <div
      data-puzzle-container="true"
      className="flex flex-col flex-1 min-h-0 overflow-y-auto overscroll-contain custom-scrollbar pb-[100px] md:pb-0"
      onDragOver={(event) => {
        if (draggingNodeId) {
          event.preventDefault()
        }
      }}
      onDrop={(event) => {
        if (draggingNodeId) {
          event.stopPropagation()
          onDrop('__end__')
        }
      }}
    >
      {nodes.map((node, index) => (
        <Card
          className={`relative grid grid-cols-[36px_minmax(0,1fr)] max-[640px]:grid-cols-[36px_minmax(0,1fr)] gap-3 min-h-[188px] max-[640px]:px-4 max-[640px]:py-[15px] shrink-0 p-4 px-5 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] transition-all duration-200 overflow-visible last:border-b-0 cursor-grab active:cursor-grabbing select-none ${
            draggingNodeId === node.id ? 'opacity-60 bg-[#fff9e8] scale-[0.985]' : ''
          } ${
            dragOverNodeId === node.id && draggingNodeId !== node.id
              ? '!border-t-4 !border-t-[#f7cd67] bg-[#fffce8]'
              : ''
          }`}
          draggable={true}
          key={`${node.id}-${node.title}`}
          onDragEnd={onDragEnd}
          onDragOver={(event) => {
            if (draggingNodeId) {
              event.preventDefault()
            }
          }}
          onDragEnter={() => {
            if (draggingNodeId && draggingNodeId !== node.id) {
              onSetDragOverNodeId(node.id)
            }
          }}
          onDragLeave={() => {
            onSetDragOverNodeId(null)
          }}
          onDragStart={(event) => {
            event.stopPropagation()
            onDragStart(node.id)
          }}
          onDrop={(event) => {
            event.stopPropagation()
            onDrop(node.id)
          }}
        >
          <div className="absolute right-4 bottom-4 w-16 h-16 text-[#725d42]/6 pointer-events-none select-none z-0">
            <svg viewBox="0 0 24 24" fill="currentColor" className="w-full h-full">
              <path d="M20 12c0-1.1-.9-2-2-2V7c0-1.1-.9-2-2-2h-3c0-1.1-.9-2-2-2s-2 .9-2 2H6c-1.1 0-2 .9-2 2v3c1.1 0 2 .9 2 2s-.9 2-2 2v3c0 1.1.9 2 2 2h3c0 1.1.9 2 2 2s2-.9 2-2h3c1.1 0 2-.9 2-2v-3c1.1 0 2-.9 2-2z" />
            </svg>
          </div>

          <div className="relative z-10 flex flex-col items-center gap-1.5 shrink-0 select-none">
            <div className="grid place-items-center w-8 h-8 rounded-full bg-[#f7cd67] text-[#725d42] font-black shadow-[0_3px_0_#dba90e]">
              {index + 1}
            </div>
            <div className="flex flex-col gap-1 mt-1">
              <button
                type="button"
                disabled={index === 0}
                className="grid place-items-center w-6 h-6 border border-animal-border rounded bg-[#fff9e8] text-[#725d42] text-xs font-black cursor-pointer hover:bg-[#ffeea0] disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-[#fff9e8] transition-colors"
                onClick={(event) => {
                  event.stopPropagation()
                  onMoveUp(node.id)
                }}
                title="上移"
              >
                上
              </button>
              <button
                type="button"
                disabled={index === nodes.length - 1}
                className="grid place-items-center w-6 h-6 border border-animal-border rounded bg-[#fff9e8] text-[#725d42] text-xs font-black cursor-pointer hover:bg-[#ffeea0] disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-[#fff9e8] transition-colors"
                onClick={(event) => {
                  event.stopPropagation()
                  onMoveDown(node.id)
                }}
                title="下移"
              >
                下
              </button>
            </div>
          </div>
          <article className="relative z-10 flex flex-col min-w-0 flex-1">
            <div className="flex items-center justify-between gap-2.5 min-w-0 max-[640px]:flex-col max-[640px]:items-stretch">
              <strong className="min-w-0 overflow-hidden text-[#794f27] text-sm font-black text-ellipsis whitespace-nowrap">
                {node.time}
              </strong>
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#e6f9f6] text-[#11a89b] text-[11px] font-black shrink-0 whitespace-nowrap max-[640px]:self-start">
                {node.status}
              </span>
            </div>
            <h3 className="mt-1.25 mb-0 text-[#794f27] text-lg font-black leading-snug">{node.title}</h3>
            <button
              className="inline-flex w-fit mt-1.25 p-0 border-0 border-b-2 border-[#9a835a]/30 bg-transparent text-[#9a835a] font-black text-sm leading-snug text-left cursor-pointer hover:text-[#794f27] hover:border-b-[#f7cd67] transition-all"
              type="button"
              onClick={(event) => {
                event.stopPropagation()
                onOpenMerchant(node.place)
              }}
            >
              {node.place}
            </button>
            <p className="mt-1.25 mb-0 text-[#725d42] text-sm font-semibold leading-relaxed">{node.reason}</p>
            <div className="flex flex-wrap items-center gap-2 mt-2.5">
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap">
                {node.audience}
              </span>
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap">
                {node.budget}
              </span>
            </div>

            {editingNodeId === node.id ? (
              <div className="grid grid-cols-[1fr_auto] max-[640px]:grid-cols-1 items-center gap-2 mt-2.5 pt-0">
                <Input
                  allowClear
                  value={nodeDraft}
                  placeholder="描述你想怎么调整这个节点"
                  onChange={(event) => onSetNodeDraft(event.target.value)}
                  onClear={() => onSetNodeDraft('')}
                />
                <Button type="primary" disabled={!nodeDraft.trim()} onClick={() => onApplyRewrite(node.id)}>
                  生成
                </Button>
              </div>
            ) : (
              <div className="flex flex-wrap items-center gap-2 mt-2.5 pt-0">
                <Button
                  type="default"
                  size="small"
                  className="min-h-[30px]! px-[13px]! text-[12px]!"
                  onClick={() => onReplace(node.id)}
                >
                  换一个
                </Button>
                <Button
                  type="dashed"
                  size="small"
                  className="min-h-[30px]! px-[13px]! text-[12px]!"
                  onClick={() => onEdit(node.id)}
                >
                  描述修改
                </Button>
              </div>
            )}
          </article>
        </Card>
      ))}
    </div>
  )
}
