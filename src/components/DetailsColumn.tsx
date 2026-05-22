import { Card } from 'animal-island-ui'
import type { PlanNode } from '../types/plan'

type DetailsColumnProps = {
  nodes: PlanNode[]
}

export function DetailsColumn({ nodes }: DetailsColumnProps) {
  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-y-auto overscroll-contain custom-scrollbar pb-[100px] md:pb-0">
      {nodes.map((node) => (
        <Card
          className="flex flex-col min-h-[188px] max-[640px]:px-4 max-[640px]:py-[15px] shrink-0 p-4 px-5 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] transition-all duration-200 overflow-visible last:border-b-0"
          key={node.id}
        >
          <article className="flex flex-col min-w-0 flex-1">
            <div className="flex items-center justify-between gap-2.5 min-w-0 max-[640px]:flex-col max-[640px]:items-stretch">
              <strong className="min-w-0 overflow-hidden text-[#794f27] text-sm font-black text-ellipsis whitespace-nowrap">
                {node.time} 路 {node.place}
              </strong>
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#e6f9f6] text-[#11a89b] text-[11px] font-black shrink-0 whitespace-nowrap max-[640px]:self-start">
                {node.status}
              </span>
            </div>
            <h3 className="mt-1.25 mb-0 text-[#794f27] text-lg font-black leading-snug">{node.title}</h3>
            <p className="mt-1.25 mb-0 text-[#725d42] text-sm font-semibold leading-relaxed">{node.details}</p>
            <div className="flex flex-wrap items-center gap-2 mt-2.5">
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap">
                {node.audience}
              </span>
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap">
                {node.budget}
              </span>
            </div>
          </article>
        </Card>
      ))}
    </div>
  )
}
