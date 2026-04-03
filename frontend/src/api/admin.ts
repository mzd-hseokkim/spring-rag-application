function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  const token = localStorage.getItem('accessToken');
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

// --- 사용자 ---
export async function fetchAdminUsers(page = 0, size = 20, q?: string) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (q) params.set('q', q);
  const res = await fetch(`/api/admin/users?${params}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch users');
  return res.json();
}

export async function updateUserRole(id: string, role: string) {
  const res = await fetch(`/api/admin/users/${id}/role`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ role }),
  });
  if (!res.ok) throw new Error('Failed to update role');
  return res.json();
}

export async function deleteUser(id: string) {
  const res = await fetch(`/api/admin/users/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ message: '삭제에 실패했습니다.' }));
    throw new Error(body.message);
  }
}

// --- 문서 ---
export async function fetchAdminDocuments(page = 0, size = 20) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  const res = await fetch(`/api/admin/documents?${params}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch documents');
  return res.json();
}

export async function updateDocumentPublic(id: string, isPublic: boolean) {
  const res = await fetch(`/api/admin/documents/${id}/public`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ isPublic }),
  });
  if (!res.ok) throw new Error('Failed to update document');
  return res.json();
}

export async function deleteAdminDocument(id: string) {
  const res = await fetch(`/api/admin/documents/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to delete document');
}

// --- 재인덱싱 ---
export async function reindexDocument(id: string) {
  const res = await fetch(`/api/admin/documents/${id}/reindex`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to start reindex');
  return res.json();
}

export async function reindexAll() {
  const res = await fetch(`/api/admin/documents/reindex-all`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to start reindex-all');
  return res.json();
}

// --- 모델 단가 ---
export interface ModelPricing {
  id: string;
  modelName: string;
  inputPricePer1m: number;
  outputPricePer1m: number;
  currency: string;
  updatedAt: string;
}

export async function fetchModelPricing(): Promise<ModelPricing[]> {
  const res = await fetch('/api/admin/settings/model-pricing', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch model pricing');
  return res.json();
}

export async function upsertModelPricing(data: { modelName: string; inputPricePer1m: number; outputPricePer1m: number; currency?: string }): Promise<ModelPricing> {
  const res = await fetch('/api/admin/settings/model-pricing', {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error('Failed to update model pricing');
  return res.json();
}

export async function deleteModelPricing(id: string) {
  const res = await fetch(`/api/admin/settings/model-pricing/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to delete model pricing');
}

// --- 설정 ---
export interface ChunkingSettings {
  mode: string;
  fixedChunkSize: number;
  fixedOverlap: number;
  semanticBufferSize: number;
  semanticBreakpointPercentile: number;
  semanticMinChunkSize: number;
  semanticMaxChunkSize: number;
  childChunkSize: number;
  childOverlap: number;
}

export interface EmbeddingSettings {
  batchSize: number;
  concurrency: number;
}

export async function fetchChunkingSettings(): Promise<ChunkingSettings> {
  const res = await fetch('/api/admin/settings/chunking', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch chunking settings');
  return res.json();
}

export async function updateChunkingSettings(settings: ChunkingSettings): Promise<ChunkingSettings> {
  const res = await fetch('/api/admin/settings/chunking', {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(settings),
  });
  if (!res.ok) throw new Error('Failed to update chunking settings');
  return res.json();
}

export async function fetchEmbeddingSettings(): Promise<EmbeddingSettings> {
  const res = await fetch('/api/admin/settings/embedding', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch embedding settings');
  return res.json();
}

export async function updateEmbeddingSettings(settings: EmbeddingSettings): Promise<EmbeddingSettings> {
  const res = await fetch('/api/admin/settings/embedding', {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(settings),
  });
  if (!res.ok) throw new Error('Failed to update embedding settings');
  return res.json();
}

// --- 감사 로그 ---
export async function fetchAuditLogs(page = 0, size = 30, action?: string) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (action) params.set('action', action);
  const res = await fetch(`/api/admin/audit-logs?${params}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch audit logs');
  return res.json();
}

// --- 생성 작업 ---
export async function fetchAdminGenerations(page = 0, size = 20, status?: string) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) params.set('status', status);
  const res = await fetch(`/api/admin/generations?${params}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch generations');
  return res.json();
}

export async function deleteAdminGeneration(id: string) {
  const res = await fetch(`/api/admin/generations/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to delete generation');
}

export async function fetchAdminQuestionnaires(page = 0, size = 20, status?: string) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) params.set('status', status);
  const res = await fetch(`/api/admin/questionnaires?${params}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch questionnaires');
  return res.json();
}

export async function deleteAdminQuestionnaire(id: string) {
  const res = await fetch(`/api/admin/questionnaires/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to delete questionnaire');
}

// --- 대화 ---
export async function fetchAdminConversations(page = 0, size = 20) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  const res = await fetch(`/api/admin/conversations?${params}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch conversations');
  return res.json();
}

export async function fetchAdminConversationDetail(id: string) {
  const res = await fetch(`/api/admin/conversations/${id}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch conversation');
  return res.json();
}

export async function deleteAdminConversation(id: string) {
  const res = await fetch(`/api/admin/conversations/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to delete conversation');
}
