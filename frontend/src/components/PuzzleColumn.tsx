import { Button, Card, Input } from 'animal-island-ui'
import type { ChangeEvent, DragEvent } from 'react'
import type { PlanNode } from '../types/plan'

type PuzzleColumnProps = {
  draggingNodeId: string | null
  dragOverNodeId: string | null
  editingNodeId: string | null
  isGenerating?: boolean
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
  isGenerating = false,
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
      {nodes.length === 0 && (
        <Card className="relative flex min-h-[188px] shrink-0 flex-col justify-center gap-3 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] p-5 text-[#725d42] overflow-hidden">
          <div className="absolute right-5 bottom-5 w-16 h-16 text-[#725d42]/6 pointer-events-none select-none">
            <svg viewBox="0 0 24 24" fill="currentColor" className="w-full h-full animate-leaf-wiggle">
              <path d="M20 12c0-1.1-.9-2-2-2V7c0-1.1-.9-2-2-2h-3c0-1.1-.9-2-2-2s-2 .9-2 2H6c-1.1 0-2 .9-2 2v3c1.1 0 2 .9 2 2s-.9 2-2 2v3c0 1.1.9 2 2 2h3c0 1.1.9 2 2 2s2-.9 2-2h3c1.1 0 2-.9 2-2v-3c1.1 0 2-.9 2-2z" />
            </svg>
          </div>
          <span className="inline-flex w-fit items-center rounded-full bg-[#e6f9f6] px-3 py-1 text-[12px] font-black text-[#11a89b]">
            {isGenerating ? '正在理解' : '等待选择'}
          </span>
          <h3 className="m-0 text-[#794f27] text-xl font-black">等待你选择方向</h3>
          <p className="m-0 max-w-[360px] text-sm font-semibold leading-relaxed text-[#725d42]">
            PlanPal 会先和你聊清楚偏好、时间和地点，再把确定的节点放进这里。
          </p>
        </Card>
      )}
      {nodes.map((node, index) => {
        const isTransit = Boolean(node.isTransit)

        return (
        <Card
          className={`relative grid grid-cols-[36px_minmax(0,1fr)] max-[640px]:grid-cols-[36px_minmax(0,1fr)] gap-3 shrink-0 border-0 border-b-2 border-animal-border-light rounded-none transition-all duration-200 overflow-visible last:border-b-0 select-none ${
            isTransit
              ? 'min-h-[106px] px-5 py-3 bg-[#e8f4fd] !border-b-[#d0e2f3] text-[#4a6b82] cursor-default'
              : 'min-h-[188px] max-[640px]:px-4 max-[640px]:py-[15px] p-4 px-5 bg-[#f7f3df] text-[#725d42] cursor-grab active:cursor-grabbing'
          } ${
            draggingNodeId === node.id ? 'opacity-60 bg-[#fff9e8] scale-[0.985]' : ''
          } ${
            dragOverNodeId === node.id && draggingNodeId !== node.id
              ? '!border-t-4 !border-t-[#f7cd67] bg-[#fffce8]'
              : ''
            }`}
          draggable={!isTransit}
          key={`${node.id}-${node.title}`}
          onDragEnd={onDragEnd}
          onDragOver={(event: DragEvent<HTMLElement>) => {
            if (draggingNodeId && !isTransit) {
              event.preventDefault()
            }
          }}
          onDragEnter={() => {
            if (draggingNodeId && draggingNodeId !== node.id && !isTransit) {
              onSetDragOverNodeId(node.id)
            }
          }}
          onDragLeave={() => {
            onSetDragOverNodeId(null)
          }}
          onDragStart={(event: DragEvent<HTMLElement>) => {
            if (isTransit) return
            event.stopPropagation()
            onDragStart(node.id)
          }}
          onDrop={(event: DragEvent<HTMLElement>) => {
            if (isTransit) return
            event.stopPropagation()
            onDrop(node.id)
          }}
        >
          <div className="absolute right-4 bottom-4 w-16 h-16 pointer-events-none select-none z-0">
            {isTransit ? (
              <svg viewBox="0 0 24 24" fill="currentColor" className="w-full h-full text-[#4a6b82]/8">
                <path d="M4 16c0 .88.39 1.67 1 2.22V20c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h8v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1.78c.61-.55 1-1.34 1-2.22V6c0-3.5-3.58-4-8-4s-8 .5-8 4v10zm3.5 1c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zm9 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm1.5-6H6V6h12v5z" />
              </svg>
            ) : (
              <svg viewBox="0 0 24 24" fill="currentColor" className="w-full h-full text-[#725d42]/6">
                <path d="M20 12c0-1.1-.9-2-2-2V7c0-1.1-.9-2-2-2h-3c0-1.1-.9-2-2-2s-2 .9-2 2H6c-1.1 0-2 .9-2 2v3c1.1 0 2 .9 2 2s-.9 2-2 2v3c0 1.1.9 2 2 2h3c0 1.1.9 2 2 2s2-.9 2-2h3c1.1 0 2-.9 2-2v-3c1.1 0 2-.9 2-2z" />
              </svg>
            )}
          </div>

          <div className="relative z-10 flex flex-col items-center gap-1.5 shrink-0 select-none">
            <div className={`grid place-items-center w-8 h-8 rounded-full font-black ${
              isTransit
                ? 'bg-[#89bdf0] text-[#1e3d59] shadow-[0_3px_0_#5a8ebf]'
                : 'bg-[#f7cd67] text-[#725d42] shadow-[0_3px_0_#dba90e]'
            }`}>
              {isTransit ? '↝' : index + 1}
            </div>
            {!isTransit && <div className="flex flex-col gap-1 mt-1">
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
            </div>}
          </div>
          <article className="relative z-10 flex flex-col min-w-0 flex-1">
            <div className="flex items-center justify-between gap-2.5 min-w-0 max-[640px]:flex-col max-[640px]:items-stretch">
              <strong className={`min-w-0 overflow-hidden text-sm font-black text-ellipsis whitespace-nowrap ${
                isTransit ? 'text-[#2b4c6f]' : 'text-[#794f27]'
              }`}>
                {node.time}
              </strong>
              <span className={`inline-flex items-center min-h-[22px] px-2 rounded-full text-[11px] font-black shrink-0 whitespace-nowrap max-[640px]:self-start ${
                isTransit ? 'bg-[#d2e6f7] text-[#2b6cb0]' : 'bg-[#e6f9f6] text-[#11a89b]'
              }`}>
                {node.status}
              </span>
            </div>
            <h3 className={`${
              isTransit ? 'mt-1 mb-0 text-base text-[#233d59]' : 'mt-1.25 mb-0 text-lg text-[#794f27]'
            } font-black leading-snug`}>
              {node.title}
            </h3>
            <button
              className={`inline-flex w-fit mt-1.25 p-0 border-0 border-b-2 bg-transparent font-black text-sm leading-snug text-left transition-all ${
                isTransit
                  ? 'border-b-[#b8d2ec] text-[#4a6b82] cursor-default'
                  : 'border-b-[#9a835a]/30 text-[#9a835a] cursor-pointer hover:text-[#794f27] hover:border-b-[#f7cd67]'
              }`}
              type="button"
              onClick={(event) => {
                event.stopPropagation()
                if (isTransit) return
                onOpenMerchant(node.place)
              }}
            >
              {node.place}
            </button>
            <p className={`mt-1.25 mb-0 text-sm font-semibold leading-relaxed ${
              isTransit ? 'text-[#567a96]' : 'text-[#725d42]'
            }`}>
              {node.reason}
            </p>
            <div className="flex flex-wrap items-center gap-2 mt-2.5">
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap">
                {node.audience}
              </span>
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap">
                {node.budget}
              </span>
            </div>

            {!isTransit && editingNodeId === node.id ? (
              <div className="grid grid-cols-[1fr_auto] max-[640px]:grid-cols-1 items-center gap-2 mt-2.5 pt-0">
                <Input
                  allowClear
                  value={nodeDraft}
                  placeholder="描述你想怎么调整这个节点"
                  onChange={(event: ChangeEvent<HTMLInputElement>) => onSetNodeDraft(event.target.value)}
                  onClear={() => onSetNodeDraft('')}
                />
                <Button type="primary" disabled={!nodeDraft.trim()} onClick={() => onApplyRewrite(node.id)}>
                  生成
                </Button>
              </div>
            ) : !isTransit ? (
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
            ) : null}
          </article>
        </Card>
        )
      })}
    </div>
  )
}
