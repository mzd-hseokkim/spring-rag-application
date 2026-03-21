import type { Document, Conversation, ConversationDetail, DocumentTag, DocumentCollection } from '../types';

function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  const token = localStorage.getItem('accessToken');
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

export async function uploadDocument(file: File, isPublic = false): Promise<Document> {
  const formData = new FormData();
  formData.append('file', file);
  const url = isPublic ? '/api/documents?isPublic=true' : '/api/documents';
  const res = await fetch(url, {
    method: 'POST',
    headers: authHeaders(),
    body: formData,
  });
  if (!res.ok) throw new Error('Upload failed');
  return res.json();
}

export async function fetchDocuments(): Promise<Document[]> {
  const res = await fetch('/api/documents', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch documents');
  return res.json();
}

export async function fetchConversations(): Promise<Conversation[]> {
  const res = await fetch('/api/conversations', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch conversations');
  return res.json();
}

export async function fetchConversationDetail(id: string): Promise<ConversationDetail> {
  const res = await fetch(`/api/conversations/${id}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch conversation');
  return res.json();
}

export async function updateConversationTitle(id: string, title: string): Promise<Conversation> {
  const res = await fetch(`/api/conversations/${id}/title`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ title }),
  });
  if (!res.ok) throw new Error('Failed to update title');
  return res.json();
}

export async function deleteConversation(id: string): Promise<void> {
  const res = await fetch(`/api/conversations/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to delete conversation');
}

// --- 태그 ---
export async function fetchTags(): Promise<DocumentTag[]> {
  const res = await fetch('/api/tags', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch tags');
  return res.json();
}

export async function createTag(name: string): Promise<DocumentTag> {
  const res = await fetch('/api/tags', {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ name }),
  });
  if (!res.ok) throw new Error('Failed to create tag');
  return res.json();
}

export async function deleteTag(id: string): Promise<void> {
  const res = await fetch(`/api/tags/${id}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to delete tag');
}

export async function setDocumentTags(documentId: string, tagIds: string[]): Promise<Document> {
  const res = await fetch(`/api/documents/${documentId}/tags`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ tagIds }),
  });
  if (!res.ok) throw new Error('Failed to set tags');
  return res.json();
}

// --- 컬렉션 ---
export async function fetchCollections(): Promise<DocumentCollection[]> {
  const res = await fetch('/api/collections', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch collections');
  return res.json();
}

export async function createCollection(name: string, description?: string): Promise<DocumentCollection> {
  const res = await fetch('/api/collections', {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ name, description }),
  });
  if (!res.ok) throw new Error('Failed to create collection');
  return res.json();
}

export async function deleteCollection(id: string): Promise<void> {
  const res = await fetch(`/api/collections/${id}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to delete collection');
}

export async function setDocumentCollections(documentId: string, collectionIds: string[]): Promise<Document> {
  const res = await fetch(`/api/documents/${documentId}/collections`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ collectionIds }),
  });
  if (!res.ok) throw new Error('Failed to set collections');
  return res.json();
}
