// --- Types ---

export type QuestionnaireStatus = 'ANALYZING' | 'GENERATING' | 'RENDERING' | 'COMPLETE' | 'FAILED';

export interface QuestionnaireJob {
  id: string;
  title: string | null;
  status: QuestionnaireStatus;
  userInput: string | null;
  currentPersona: number;
  totalPersonas: number;
  outputFilePath: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface QuestionnaireRequest {
  customerDocumentIds: string[];
  proposalDocumentIds?: string[];
  referenceDocumentIds?: string[];
  personaIds: string[];
  userInput?: string;
  questionCount?: number;
  includeWebSearch?: boolean;
  analysisMode?: 'RAG' | 'LLM';
}

export interface QuestionnaireProgressEvent {
  eventType: string;
  status: QuestionnaireStatus | null;
  message: string | null;
  currentPersona: number | null;
  totalPersonas: number | null;
  personaName: string | null;
  downloadUrl: string | null;
}

// --- Auth helper ---

function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  const token = localStorage.getItem('accessToken');
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

// --- Questionnaire API ---

export async function startQuestionnaire(request: QuestionnaireRequest): Promise<QuestionnaireJob> {
  const res = await fetch('/api/questionnaires', {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(request),
  });
  if (!res.ok) throw new Error('Failed to start questionnaire generation');
  return res.json();
}

export async function fetchQuestionnaireJobs(): Promise<QuestionnaireJob[]> {
  const res = await fetch('/api/questionnaires', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch questionnaire jobs');
  return res.json();
}

export async function fetchQuestionnaireJob(id: string): Promise<QuestionnaireJob> {
  const res = await fetch(`/api/questionnaires/${id}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch questionnaire job');
  return res.json();
}

export async function deleteQuestionnaireJob(id: string): Promise<void> {
  const res = await fetch(`/api/questionnaires/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to delete questionnaire job');
}

export function getQuestionnaireStreamUrl(jobId: string): string {
  return `/api/questionnaires/${jobId}/stream`;
}

export function getQuestionnaireDownloadUrl(jobId: string): string {
  return `/api/questionnaires/${jobId}/download`;
}

export function getQuestionnairePreviewUrl(jobId: string): string {
  return `/api/questionnaires/${jobId}/preview`;
}
