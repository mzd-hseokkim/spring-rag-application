import type { Document, Conversation, ConversationDetail, DocumentTag, DocumentCollection } from '../types';
import { refreshToken as apiRefresh } from './auth';

function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  const token = localStorage.getItem('accessToken');
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

/**
 * 401/403 응답 시 자동으로 refresh token으로 갱신 후 재시도하는 fetch 래퍼.
 * 모든 API 모듈에서 import하여 사용.
 */
let isRefreshing = false;
let refreshPromise: Promise<void> | null = null;

async function doRefresh(): Promise<void> {
  const rt = localStorage.getItem('refreshToken');
  if (!rt) throw new Error('No refresh token');
  const result = await apiRefresh(rt);
  localStorage.setItem('accessToken', result.accessToken);
  localStorage.setItem('refreshToken', result.refreshToken);
}

export async function authFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const res = await fetch(input, init);

  if (res.status === 401 || res.status === 403) {
    // refresh 진행 중이면 대기
    if (!isRefreshing) {
      isRefreshing = true;
      refreshPromise = doRefresh().finally(() => { isRefreshing = false; refreshPromise = null; });
    }

    try {
      await refreshPromise;
    } catch {
      // refresh 실패 → 로그인 페이지로
      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
      window.location.href = '/login';
      return res;
    }

    // 새 토큰으로 재시도
    const newHeaders = new Headers(init?.headers);
    const newToken = localStorage.getItem('accessToken');
    if (newToken) newHeaders.set('Authorization', `Bearer ${newToken}`);
    return fetch(input, { ...init, headers: newHeaders });
  }

  return res;
}

export async function uploadDocument(file: File, isPublic = false): Promise<Document> {
  const formData = new FormData();
  formData.append('file', file);
  const url = isPublic ? '/api/documents?isPublic=true' : '/api/documents';
  const res = await authFetch(url, {
    method: 'POST',
    headers: authHeaders(),
    body: formData,
  });
  if (!res.ok) throw new Error('Upload failed');
  return res.json();
}

export async function fetchDocuments(): Promise<Document[]> {
  const res = await authFetch('/api/documents', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch documents');
  return res.json();
}

export async function searchDocuments(keyword: string): Promise<Document[]> {
  const res = await authFetch(`/api/documents/search?q=${encodeURIComponent(keyword)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to search documents');
  return res.json();
}

export async function fetchConversations(): Promise<Conversation[]> {
  const res = await authFetch('/api/conversations', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch conversations');
  return res.json();
}

export async function fetchConversationDetail(id: string): Promise<ConversationDetail> {
  const res = await authFetch(`/api/conversations/${id}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch conversation');
  return res.json();
}

export async function updateConversationTitle(id: string, title: string): Promise<Conversation> {
  const res = await authFetch(`/api/conversations/${id}/title`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ title }),
  });
  if (!res.ok) throw new Error('Failed to update title');
  return res.json();
}

export async function deleteConversation(id: string): Promise<void> {
  const res = await authFetch(`/api/conversations/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to delete conversation');
}

// --- 태그 ---
export async function fetchTags(): Promise<DocumentTag[]> {
  const res = await authFetch('/api/tags', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch tags');
  return res.json();
}

export async function createTag(name: string): Promise<DocumentTag> {
  const res = await authFetch('/api/tags', {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ name }),
  });
  if (!res.ok) throw new Error('Failed to create tag');
  return res.json();
}

export async function deleteTag(id: string): Promise<void> {
  const res = await authFetch(`/api/tags/${id}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to delete tag');
}

export async function setDocumentTags(documentId: string, tagIds: string[]): Promise<Document> {
  const res = await authFetch(`/api/documents/${documentId}/tags`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ tagIds }),
  });
  if (!res.ok) throw new Error('Failed to set tags');
  return res.json();
}

// --- 컬렉션 ---
export async function fetchCollections(): Promise<DocumentCollection[]> {
  const res = await authFetch('/api/collections', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch collections');
  return res.json();
}

export async function createCollection(name: string, description?: string): Promise<DocumentCollection> {
  const res = await authFetch('/api/collections', {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ name, description }),
  });
  if (!res.ok) throw new Error('Failed to create collection');
  return res.json();
}

export async function deleteCollection(id: string): Promise<void> {
  const res = await authFetch(`/api/collections/${id}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to delete collection');
}

export async function setDocumentCollections(documentId: string, collectionIds: string[]): Promise<Document> {
  const res = await authFetch(`/api/documents/${documentId}/collections`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ collectionIds }),
  });
  if (!res.ok) throw new Error('Failed to set collections');
  return res.json();
}
