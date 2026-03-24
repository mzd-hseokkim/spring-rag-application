// --- Types ---

export interface Persona {
  id: string;
  name: string;
  role: string;
  focusAreas: string | null;
  prompt: string | null;
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface PersonaRequest {
  name: string;
  role: string;
  focusAreas?: string;
  prompt?: string;
}

// --- Auth helper ---

function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  const token = localStorage.getItem('accessToken');
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

// --- Persona API ---

export async function fetchPersonas(): Promise<Persona[]> {
  const res = await fetch('/api/personas', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch personas');
  return res.json();
}

export async function createPersona(request: PersonaRequest): Promise<Persona> {
  const res = await fetch('/api/personas', {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(request),
  });
  if (!res.ok) throw new Error('Failed to create persona');
  return res.json();
}

export async function updatePersona(id: string, request: PersonaRequest): Promise<Persona> {
  const res = await fetch(`/api/personas/${id}`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(request),
  });
  if (!res.ok) throw new Error('Failed to update persona');
  return res.json();
}

export async function regeneratePersonaPrompt(id: string): Promise<Persona> {
  const res = await fetch(`/api/personas/${id}/regenerate-prompt`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to regenerate prompt');
  return res.json();
}

export async function deletePersona(id: string): Promise<void> {
  const res = await fetch(`/api/personas/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to delete persona');
}
