import type { MerchantProfile, PlanNode } from '../types/plan'

export function createFallbackMerchant(node: PlanNode): MerchantProfile {
  return {
    address: `${node.place} 附近推荐点位`,
    queue: node.status.includes('预约') ? '建议出发前先确认是否有位。' : '预计等待时间较短。',
    booking: node.details,
    hours: '建议以当天营业时间为准。',
    contact: '待接入真实商家信息',
    tags: [node.audience, node.budget, node.status],
  }
}
