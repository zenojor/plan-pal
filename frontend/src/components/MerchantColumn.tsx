import { Button, Card } from 'animal-island-ui'
import { merchantProfiles } from '../data/planData'
import type { MerchantProduct, PlanNode } from '../types/plan'
import { createFallbackMerchant } from '../utils/merchant'

type MerchantColumnProps = {
  nodes: PlanNode[]
  onSelectPlace: (place: string) => void
  selectedPlace: string | null
}

function productTone(index: number) {
  return [
    'from-[#f7cd67] to-[#fff3c4]',
    'from-[#82d5bb] to-[#e6f9f6]',
    'from-[#f8a6b2] to-[#fff0f3]',
    'from-[#889df0] to-[#e8ecfd]',
  ][index % 4]
}

function productInitial(product: MerchantProduct) {
  return product.name.replace(/[【】[\]\s]/g, '').slice(0, 2) || '品'
}

export function MerchantColumn({
  nodes,
  onSelectPlace,
  selectedPlace,
}: MerchantColumnProps) {
  let displayNodes = nodes.filter((node) => !node.isTransit)
  if (displayNodes.length === 0 && selectedPlace) {
    displayNodes = [
      {
        id: 'temp-merchant-consult',
        time: '咨询推荐',
        title: '灵感推荐商户',
        place: selectedPlace,
        lnglat: [121.4737, 31.2304],
        audience: '出行推荐',
        reason: '这是系统为你筛选出的推荐商户，可以在对话区一键合成为完整行程。',
        budget: '参考商户详情',
        status: '推荐中',
        details: '点击对话区的同意并构建行程按钮即可生成拼图。',
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
        const fallbackProfile = merchantProfiles[node.place] ?? createFallbackMerchant(node)
        const profile = {
          ...fallbackProfile,
          address: node.address || fallbackProfile.address,
          hours: node.businessHours || fallbackProfile.hours,
          contact: node.telephone || fallbackProfile.contact,
          tags: [node.typeCode, node.source, ...fallbackProfile.tags].filter(Boolean).slice(0, 8) as string[],
        }
        const products = profile.products?.length ? profile.products : createFallbackMerchant(node).products || []
        const isSelected = node.place === selectedNode?.place

        return (
          <Card
            className={`flex flex-col max-[640px]:px-4 max-[640px]:py-[15px] shrink-0 p-4 px-5 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] transition-all duration-200 overflow-visible last:border-b-0 hover:!translate-y-0 ${isSelected ? 'bg-[#fff9e8]!' : ''}`}
            key={`${node.id}-${node.place}`}
          >
            <article className="flex flex-col min-w-0 flex-1">
              <div className="grid grid-cols-[92px_minmax(0,1fr)] gap-3">
                {profile.thumbnail ? (
                  <img
                    src={profile.thumbnail}
                    alt=""
                    className="h-[92px] w-[92px] rounded-[18px] border-2 border-[#c4b89e] object-cover shadow-[0_3px_0_0_#d4c9b4]"
                  />
                ) : (
                  <div className="grid h-[92px] w-[92px] place-items-center rounded-[18px] border-2 border-[#c4b89e] bg-gradient-to-br from-[#f7cd67] to-[#82d5bb] text-xl font-black text-[#794f27] shadow-[0_3px_0_0_#d4c9b4]">
                    {node.place.slice(0, 2)}
                  </div>
                )}

                <div className="min-w-0">
                  <div className="flex items-center justify-between gap-2 min-w-0">
                    <strong className="min-w-0 overflow-hidden text-[#794f27] text-sm font-black text-ellipsis whitespace-nowrap">
                      {isSelected ? '正在查看' : node.time}
                    </strong>
                    <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#e6f9f6] text-[#11a89b] text-[11px] font-black shrink-0 whitespace-nowrap">
                      {index === 0 ? '已选中' : node.status}
                    </span>
                  </div>
                  <h3 className="mt-1 mb-0 text-[#794f27] text-lg font-black leading-snug line-clamp-2">{node.place}</h3>
                  <div className="mt-1 flex flex-wrap items-center gap-1.5 text-[12px] font-black">
                    <span className="rounded-full bg-[#fff3c4] px-2 py-0.5 text-[#d9791c]">{profile.rating || '4.7'} 分</span>
                    <span className="rounded-full bg-[#e6f9f6] px-2 py-0.5 text-[#0f4c46]">{profile.avgPrice || node.budget}</span>
                  </div>
                  <p className="mt-1.5 mb-0 line-clamp-2 text-[#725d42] text-xs font-bold leading-relaxed">{profile.address}</p>
                </div>
              </div>

              <dl className="grid gap-1.5 mt-3 rounded-[18px] border-2 border-[#c4b89e] bg-[#fff9e8] px-3 py-2 shadow-[0_2px_0_0_#d4c9b4]">
                <div className="grid grid-cols-[48px_minmax(0,1fr)] gap-2 items-start">
                  <dt className="m-0 text-[#9a835a] font-black text-[12px] leading-[1.4]">营业</dt>
                  <dd className="m-0 text-[#725d42] font-bold text-[12px] leading-[1.4]">{profile.hours}</dd>
                </div>
                <div className="grid grid-cols-[48px_minmax(0,1fr)] gap-2 items-start">
                  <dt className="m-0 text-[#9a835a] font-black text-[12px] leading-[1.4]">排队</dt>
                  <dd className="m-0 text-[#725d42] font-bold text-[12px] leading-[1.4]">{profile.queue}</dd>
                </div>
                <div className="grid grid-cols-[48px_minmax(0,1fr)] gap-2 items-start">
                  <dt className="m-0 text-[#9a835a] font-black text-[12px] leading-[1.4]">预约</dt>
                  <dd className="m-0 text-[#725d42] font-bold text-[12px] leading-[1.4]">{profile.booking}</dd>
                </div>
                <div className="grid grid-cols-[48px_minmax(0,1fr)] gap-2 items-start">
                  <dt className="m-0 text-[#9a835a] font-black text-[12px] leading-[1.4]">电话</dt>
                  <dd className="m-0 text-[#725d42] font-bold text-[12px] leading-[1.4]">{profile.contact}</dd>
                </div>
              </dl>

              <div className="flex flex-wrap items-center gap-2 mt-3">
                {profile.tags.map((tag) => (
                  <span
                    key={tag}
                    className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap"
                  >
                    {tag}
                  </span>
                ))}
              </div>

              <div className="mt-4">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <h4 className="m-0 text-sm font-black text-[#794f27]">商品列表</h4>
                  <span className="rounded-full bg-[#e6f9f6] px-2 py-0.5 text-[11px] font-black text-[#11a89b]">
                    {products.length} 个可看
                  </span>
                </div>
                <div className="grid gap-2">
                  {products.map((product, productIndex) => (
                    <div
                      className="grid grid-cols-[72px_minmax(0,1fr)] gap-3 rounded-[18px] border-2 border-[#c4b89e] bg-[#fdfdf5] p-2 shadow-[0_2px_0_0_#d4c9b4]"
                      key={product.id}
                    >
                      {product.thumbnail ? (
                        <img src={product.thumbnail} alt="" className="h-[72px] w-[72px] rounded-[14px] object-cover" />
                      ) : (
                        <div className={`grid h-[72px] w-[72px] place-items-center rounded-[14px] bg-gradient-to-br ${productTone(productIndex)} text-lg font-black text-[#794f27]`}>
                          {productInitial(product)}
                        </div>
                      )}
                      <div className="min-w-0">
                        <h5 className="m-0 line-clamp-2 text-sm font-black leading-snug text-[#794f27]">{product.name}</h5>
                        <p className="m-0 mt-1 line-clamp-2 text-[12px] font-bold leading-relaxed text-[#725d42]">{product.description}</p>
                        <div className="mt-1 flex flex-wrap gap-1">
                          {product.tags.slice(0, 3).map((tag) => (
                            <span key={tag} className="rounded-full bg-[#e6f9f6] px-1.5 py-0.5 text-[10px] font-black text-[#0f4c46]">
                              {tag}
                            </span>
                          ))}
                        </div>
                        <div className="mt-2 flex items-end justify-between gap-2">
                          <div className="min-w-0">
                            <span className="text-lg font-black text-[#e05a5a]">¥{product.price}</span>
                            {product.originalPrice && (
                              <span className="ml-1 text-[11px] font-bold text-[#9f927d] line-through">¥{product.originalPrice}</span>
                            )}
                            <p className="m-0 text-[10px] font-bold text-[#9f927d]">
                              {product.rating || '90%好评'} · {product.sold || '近期可订'}
                            </p>
                          </div>
                          <Button type="primary" size="small" className="min-h-[28px]! px-3! text-[11px]!">
                            查看
                          </Button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {!isSelected && (
                <div className="flex flex-wrap items-center gap-2 mt-3 pt-0">
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
