const DEFAULT_API_BASE_URL = 'http://localhost:8081'

function trimTrailingSlash(value: string) {
  return value.replace(/\/+$/, '')
}

export const API_BASE_URL = trimTrailingSlash(
  import.meta.env.VITE_API_BASE_URL?.trim() || DEFAULT_API_BASE_URL,
)

export const agentApi = {
  health: `${API_BASE_URL}/api/v1/agent/health`,
  plan: `${API_BASE_URL}/api/v1/agent/plan`,
  planStream: `${API_BASE_URL}/api/v1/agent/plan/stream`,
  confirmPlan: (planId: string) => `${API_BASE_URL}/api/v1/agent/plan/${planId}/confirm`,
}
