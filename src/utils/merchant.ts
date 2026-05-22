import type { MerchantProfile, PlanNode } from '../types/plan'

export function createFallbackMerchant(node: PlanNode): MerchantProfile {
  return {
    address: `${node.place} recommended local spot`,
    queue: node.status.includes('Book') ? 'Confirm availability before heading over.' : 'Expected wait is short.',
    booking: node.details,
    hours: 'Check same-day hours before arrival.',
    contact: 'Pending real venue integration',
    tags: [node.audience, node.budget, node.status],
  }
}
