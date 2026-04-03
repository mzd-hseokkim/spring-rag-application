function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('accessToken');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function get(url: string) {
  const res = await fetch(url, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to fetch ${url}`);
  return res.json();
}

export const dashboardApi = {
  overview: () => get('/api/admin/dashboard/overview'),
  chatTrend: (days = 30) => get(`/api/admin/dashboard/chat-trend?days=${days}`),
  agentDistribution: () => get('/api/admin/dashboard/agent-distribution'),
  tokenTrend: (days = 30, purpose?: string) => get(`/api/admin/dashboard/token-trend?days=${days}${purpose ? `&purpose=${purpose}` : ''}`),
  tokenByUser: (days = 30, purpose?: string) => get(`/api/admin/dashboard/token-by-user?days=${days}${purpose ? `&purpose=${purpose}` : ''}`),
  tokenByModel: (days = 30, purpose?: string) => get(`/api/admin/dashboard/token-by-model?days=${days}${purpose ? `&purpose=${purpose}` : ''}`),
  tokenByPurpose: (days = 30) => get(`/api/admin/dashboard/token-by-purpose?days=${days}`),
  tokenCost: (days = 30) => get(`/api/admin/dashboard/token-cost?days=${days}`),
  tokenCostByUser: (days = 30) => get(`/api/admin/dashboard/token-cost-by-user?days=${days}`),
  generationTrend: (days = 30) => get(`/api/admin/dashboard/generation-trend?days=${days}`),
  traces: (page = 0, size = 20) => get(`/api/admin/dashboard/traces?page=${page}&size=${size}`),
  generationTraces: (page = 0, size = 20) => get(`/api/admin/dashboard/generation-traces?page=${page}&size=${size}`),
};
