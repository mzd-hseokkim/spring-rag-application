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
