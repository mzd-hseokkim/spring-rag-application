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
  tokenTrend: (days = 30) => get(`/api/admin/dashboard/token-trend?days=${days}`),
  tokenByUser: (days = 30) => get(`/api/admin/dashboard/token-by-user?days=${days}`),
  tokenByModel: (days = 30) => get(`/api/admin/dashboard/token-by-model?days=${days}`),
  traces: (page = 0, size = 20) => get(`/api/admin/dashboard/traces?page=${page}&size=${size}`),
};
