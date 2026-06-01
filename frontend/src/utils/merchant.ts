import type { MerchantProfile, PlanNode } from '../types/plan'

function firstNumber(value: string | undefined, fallback: number) {
  const match = value?.match(/\d+/)
  return match ? Number.parseInt(match[0], 10) : fallback
}

export function createFallbackMerchant(node: PlanNode): MerchantProfile {
  const basePrice = firstNumber(node.budget, 68)
  const tags = [node.typeCode, node.audience, node.budget, node.status]
    .filter(Boolean)
    .slice(0, 6) as string[]

  return {
    address: node.address || `${node.place} 附近`,
    queue: node.status.includes('预约') ? '建议出发前确认是否有位。' : '预计等待时间较短。',
    booking: node.details || '到店前可先电话确认。',
    hours: node.businessHours || '建议以当天营业时间为准。',
    contact: node.telephone || '待接入真实商家信息',
    tags,
    rating: '4.7',
    avgPrice: node.budget || `人均 CNY ${basePrice}`,
    products: [
      {
        id: `${node.id}-featured`,
        name: `${node.place} 推荐套餐`,
        description: node.reason || '按当前行程偏好生成的推荐商品。',
        price: basePrice,
        originalPrice: basePrice + 18,
        tags: ['严选好品', '适合本方案'],
        sold: '月售 200+',
        rating: '95%好评',
      },
      {
        id: `${node.id}-light`,
        name: '轻量体验/到店单品',
        description: node.details || '到店后可灵活选择，适合不想提前锁定太多内容。',
        price: Math.max(29, Math.round(basePrice * 0.72)),
        tags: ['随时退', '低压力'],
        sold: '近期热卖',
        rating: '90%好评',
      },
    ],
  }
}
