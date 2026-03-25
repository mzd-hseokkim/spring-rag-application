import { authFetch } from './client';

// --- Types ---

export type GenerationStatus = 'PLANNING' | 'GENERATING' | 'REVIEWING' | 'RENDERING' | 'COMPLETE' | 'FAILED' | 'DRAFT' | 'ANALYZING' | 'MAPPING' | 'READY';
export type OutputFormat = 'PDF' | 'PPTX' | 'HTML';

export interface DocumentTemplate {
  id: string;
  name: string;
  description: string;
  outputFormat: OutputFormat;
  sectionSchema: string;
  systemPrompt: string;
  templatePath: string;
  isPublic: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface GenerationJob {
  id: string;
  status: GenerationStatus;
  templateId: string;
  templateName: string;
  title: string | null;
  userInput: string | null;
  currentSection: number;
  totalSections: number;
  currentStep: number;
  stepStatus: string;
  outline: string | null;
  requirementMapping: string | null;
  generatedSections: string | null;
  includeWebSearch: boolean;
  outputFilePath: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface OutlineNode {
  key: string;
  title: string;
  description: string;
  children: OutlineNode[];
}

export interface GenerationRequest {
  templateId: string;
  userInput: string;
  conversationId?: string;
  customerDocumentIds?: string[];
  referenceDocumentIds?: string[];
  includeWebSearch?: boolean;
  options?: {
    includeReview?: boolean;
    tagIds?: string[];
    collectionIds?: string[];
    documentIds?: string[];
  };
}

export interface GenerationProgressEvent {
  eventType: string;
  status: GenerationStatus | null;
  message: string | null;
  currentSection: number | null;
  totalSections: number | null;
  sectionTitle: string | null;
  sectionKey: string | null;
  downloadUrl: string | null;
}

// --- Auth helper ---

function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  const token = localStorage.getItem('accessToken');
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

// --- Template API ---

export async function fetchTemplates(): Promise<DocumentTemplate[]> {
  const res = await authFetch('/api/templates', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch templates');
  return res.json();
}

export async function fetchTemplate(id: string): Promise<DocumentTemplate> {
  const res = await authFetch(`/api/templates/${id}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch template');
  return res.json();
}

// --- Generation API ---

export async function startGeneration(request: GenerationRequest): Promise<GenerationJob> {
  const res = await authFetch('/api/generations', {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(request),
  });
  if (!res.ok) throw new Error('Failed to start generation');
  return res.json();
}

export async function fetchJob(id: string): Promise<GenerationJob> {
  const res = await authFetch(`/api/generations/${id}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch generation job');
  return res.json();
}

export async function fetchJobs(): Promise<GenerationJob[]> {
  const res = await authFetch('/api/generations', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch generation jobs');
  return res.json();
}

export async function deleteJob(id: string): Promise<void> {
  const res = await authFetch(`/api/generations/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to delete generation job');
}

export async function startOutlineExtraction(jobId: string, customerDocumentIds: string[]): Promise<void> {
  const res = await authFetch(`/api/generations/${jobId}/analyze`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ customerDocumentIds }),
  });
  if (!res.ok) throw new Error('Failed to start outline extraction');
}

export async function saveOutline(jobId: string, outline: OutlineNode[]): Promise<GenerationJob> {
  const res = await authFetch(`/api/generations/${jobId}/outline`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(outline),
  });
  if (!res.ok) throw new Error('Failed to save outline');
  return res.json();
}

export async function startRequirementMapping(jobId: string, customerDocumentIds: string[]): Promise<void> {
  const res = await authFetch(`/api/generations/${jobId}/map-requirements`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ customerDocumentIds }),
  });
  if (!res.ok) throw new Error('Failed to start requirement mapping');
}

export async function saveRequirementMapping(jobId: string, mapping: unknown): Promise<GenerationJob> {
  const res = await authFetch(`/api/generations/${jobId}/requirements`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(mapping),
  });
  if (!res.ok) throw new Error('Failed to save requirement mapping');
  return res.json();
}

export async function startSectionGeneration(jobId: string, referenceDocumentIds?: string[], includeWebSearch?: boolean, sectionKeys?: string[]): Promise<void> {
  const res = await authFetch(`/api/generations/${jobId}/generate-sections`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ referenceDocumentIds: referenceDocumentIds || [], includeWebSearch: includeWebSearch || false, sectionKeys: sectionKeys || [] }),
  });
  if (!res.ok) throw new Error('Failed to start section generation');
}

export async function regenerateSection(jobId: string, sectionKey: string, referenceDocumentIds?: string[], includeWebSearch?: boolean): Promise<void> {
  const res = await authFetch(`/api/generations/${jobId}/regenerate-section/${sectionKey}`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ referenceDocumentIds: referenceDocumentIds || [], includeWebSearch: includeWebSearch || false }),
  });
  if (!res.ok) throw new Error('Failed to regenerate section');
}

export async function clearSections(jobId: string): Promise<void> {
  const res = await authFetch(`/api/generations/${jobId}/sections`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to clear sections');
}

export async function saveSection(jobId: string, sectionKey: string, section: unknown): Promise<GenerationJob> {
  const res = await authFetch(`/api/generations/${jobId}/sections/${sectionKey}`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(section),
  });
  if (!res.ok) throw new Error('Failed to save section');
  return res.json();
}

export async function startRendering(jobId: string): Promise<void> {
  const res = await authFetch(`/api/generations/${jobId}/render`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to start rendering');
}

export function getStreamUrl(jobId: string): string {
  return `/api/generations/${jobId}/stream`;
}

export function getDownloadUrl(jobId: string): string {
  return `/api/generations/${jobId}/download`;
}

export function getPreviewUrl(jobId: string): string {
  return `/api/generations/${jobId}/preview`;
}
