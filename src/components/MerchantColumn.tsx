import { Button, Card } from 'animal-island-ui'
import { merchantProfiles } from '../data/planData'
import type { PlanNode } from '../types/plan'
import { createFallbackMerchant } from '../utils/merchant'

type MerchantColumnProps = {
  nodes: PlanNode[]
  onSelectPlace: (place: string) => void
  selectedPlace: string | null
}

export function MerchantColumn({
  nodes,
  onSelectPlace,
  selectedPlace,
}: MerchantColumnProps) {
  let displayNodes = nodes
  if (displayNodes.length === 0 && selectedPlace) {
    displayNodes = [
      {
        id: 'temp-merchant-consult',
        time: '咨询推荐',
        title: '灵感推荐商户',
        place: selectedPlace,
        lnglat: [121.4737, 31.2304],
        audience: '出行推荐',
        reason: '这是系统为您筛选出排队及状态最佳的灵感推荐商户，您可以在对话区一键将它合成为完整的出行时间线拼图。',
        budget: '参见商户详情',
        status: '推荐中',
        details: '点击左侧气泡下方的“同意并构建行程”按钮即可一键合成。',
      },
    ]
  }

  const selectedNode =
    displayNodes.find((node) => node.place === selectedPlace) ?? displayNodes.find((node) => node.id !== 'start')
  const orderedNodes = selectedNode
    ? [selectedNode, ...displayNodes.filter((node) => node.place !== selectedNode.place)]
    : displayNodes

  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-y-auto overscroll-contain custom-scrollbar pb-[100px] md:pb-0">
      {orderedNodes.map((node, index) => {
        const profile = merchantProfiles[node.place] ?? createFallbackMerchant(node)
        const isSelected = node.place === selectedNode?.place

        return (
          <Card
            className={`flex flex-col min-h-[188px] max-[640px]:px-4 max-[640px]:py-[15px] shrink-0 p-4 px-5 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] transition-all duration-200 overflow-visible last:border-b-0 ${isSelected ? 'bg-[#fff9e8]!' : ''}`}
            key={`${node.id}-${node.place}`}
          >
            <article className="flex flex-col min-w-0 flex-1">
              <div className="flex items-center justify-between gap-2.5 min-w-0 max-[640px]:flex-col max-[640px]:items-stretch">
                <strong className="min-w-0 overflow-hidden text-[#794f27] text-sm font-black text-ellipsis whitespace-nowrap">
                  {isSelected ? '正在查看' : node.time}
                </strong>
                <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#e6f9f6] text-[#11a89b] text-[11px] font-black shrink-0 whitespace-nowrap max-[640px]:self-start">
                  {index === 0 ? '已选中' : node.status}
                </span>
              </div>
              <h3 className="mt-1.25 mb-0 text-[#794f27] text-lg font-black leading-snug">{node.place}</h3>
              <p className="mt-1.25 mb-0 text-[#725d42] text-sm font-semibold leading-relaxed">{profile.address}</p>
              <dl className="grid gap-1.75 mt-2.5">
                <div className="grid grid-cols-[42px_minmax(0,1fr)] gap-2 items-start">
                  <dt className="m-0 text-[#9a835a] font-black text-[13px] leading-[1.4]">营业</dt>
                  <dd className="m-0 text-[#725d42] font-bold text-[13px] leading-[1.4]">{profile.hours}</dd>
                </div>
                <div className="grid grid-cols-[42px_minmax(0,1fr)] gap-2 items-start">
                  <dt className="m-0 text-[#9a835a] font-black text-[13px] leading-[1.4]">排队</dt>
                  <dd className="m-0 text-[#725d42] font-bold text-[13px] leading-[1.4]">{profile.queue}</dd>
                </div>
                <div className="grid grid-cols-[42px_minmax(0,1fr)] gap-2 items-start">
                  <dt className="m-0 text-[#9a835a] font-black text-[13px] leading-[1.4]">预约</dt>
                  <dd className="m-0 text-[#725d42] font-bold text-[13px] leading-[1.4]">{profile.booking}</dd>
                </div>
                <div className="grid grid-cols-[42px_minmax(0,1fr)] gap-2 items-start">
                  <dt className="m-0 text-[#9a835a] font-black text-[13px] leading-[1.4]">电话</dt>
                  <dd className="m-0 text-[#725d42] font-bold text-[13px] leading-[1.4]">{profile.contact}</dd>
                </div>
              </dl>
              <div className="flex flex-wrap items-center gap-2 mt-2.5">
                {profile.tags.map((tag) => (
                  <span
                    key={tag}
                    className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap"
                  >
                    {tag}
                  </span>
                ))}
              </div>
              {!isSelected && (
                <div className="flex flex-wrap items-center gap-2 mt-2.5 pt-0">
                  <Button
                    type="default"
                    size="small"
                    className="min-h-[30px]! px-[13px]! text-[12px]!"
                    onClick={() => onSelectPlace(node.place)}
                  >
                    看这个
                  </Button>
                </div>
              )}
            </article>
          </Card>
        )
      })}
    </div>
  )
}
