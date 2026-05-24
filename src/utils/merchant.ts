import type { MerchantProfile, PlanNode } from '../types/plan'

export function createFallbackMerchant(node: PlanNode): MerchantProfile {
  return {
    address: node.address || `${node.place} nearby`,
    queue: node.status.includes('预约') ? '建议出发前确认是否有位。' : '预计等待时间较短。',
    booking: node.details,
    hours: node.businessHours || '建议以当天营业时间为准。',
    contact: node.telephone || '待接入真实商家信息',
    tags: [node.typeCode, node.audience, node.budget, node.status].filter(Boolean) as string[],
  }
}
