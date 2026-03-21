function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  const token = localStorage.getItem('accessToken');
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

export async function fetchEvalRuns() {
  const res = await fetch('/api/admin/eval/runs', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch eval runs');
  return res.json();
}

export async function fetchEvalRun(id: string) {
  const res = await fetch(`/api/admin/eval/runs/${id}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch eval run');
  return res.json();
}

export async function fetchEvalQuestions(runId: string) {
  const res = await fetch(`/api/admin/eval/runs/${runId}/questions`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch questions');
  return res.json();
}

export async function generateEval(name: string, documentIds: string[], questionsPerChunk: number) {
  const res = await fetch('/api/admin/eval/generate', {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ name, documentIds, questionsPerChunk }),
  });
  if (!res.ok) throw new Error('Failed to generate eval');
  return res.json();
}

export async function executeEvalRun(id: string) {
  const res = await fetch(`/api/admin/eval/runs/${id}/execute`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to execute eval');
  return res.json();
}

export async function fetchQuestionDetail(qid: string) {
  const res = await fetch(`/api/admin/eval/questions/${qid}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch question detail');
  return res.json();
}

export async function deleteEvalRun(id: string) {
  const res = await fetch(`/api/admin/eval/runs/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to delete eval run');
}
